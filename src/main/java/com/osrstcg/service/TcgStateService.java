package com.osrstcg.service;

import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.PackCardResult;
import com.osrstcg.model.RewardTuningState;
import com.osrstcg.model.TcgState;
import com.osrstcg.persist.TcgStateLoadResult;
import com.osrstcg.persist.TcgStateLoadSource;
import com.osrstcg.persist.TcgStateStore;
import com.osrstcg.util.CollectionAlbumWindowSizeUtil;
import com.osrstcg.util.PackRevealZoomUtil;
import com.google.inject.name.Named;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TcgStateService
{
	private static final long FILE_BACKUP_THROTTLE_MS = 5L * 60L * 1000L;

	private final TcgStateStore stateStore;
	private final boolean runeliteDeveloperMode;
	private volatile TcgState state = TcgState.empty();
	private Runnable rewardTuningFlushBeforeCredits;
	private final CopyOnWriteArrayList<Runnable> collectionChangeListeners = new CopyOnWriteArrayList<>();
	private long lastFileBackupEpochMs;

	@Inject
	public TcgStateService(TcgStateStore stateStore, @Named("developerMode") boolean runeliteDeveloperMode)
	{
		this.stateStore = stateStore;
		this.runeliteDeveloperMode = runeliteDeveloperMode;
	}

	TcgStateService(TcgState initialState)
	{
		this.stateStore = null;
		this.runeliteDeveloperMode = false;
		this.state = initialState == null ? TcgState.empty() : initialState;
	}

	/**
	 * Loads persisted state for the current RS profile.
	 */
	public synchronized TcgStateLoadResult load()
	{
		if (stateStore == null)
		{
			return new TcgStateLoadResult(state, TcgStateLoadSource.PRIMARY, false, false, false);
		}

		TcgStateLoadResult result = stateStore.load();
		state = result.getState();
		// Allow a file backup soon after login; plugin start may have already written one.
		lastFileBackupEpochMs = 0L;
		if (shouldResetDebugTaintedSave())
		{
			log.info("OSRS TCG: loaded profile had debug mode enabled; resetting collection and economy.");
			resetAll();
			return new TcgStateLoadResult(
				state,
				result.getSource(),
				result.isPrimaryLoadFailed(),
				result.isConfigBackupLoadFailed(),
				result.isFileBackupLoadFailed(),
				true);
		}

		if (state.isDebugLogging() && runeliteDeveloperMode)
		{
			log.info("OSRS TCG: loaded profile had debug mode enabled; keeping collection (developer mode active).");
		}

		if (stripDebugProvenanceRowsIfDebugDisabled())
		{
			save();
			notifyCollectionShareListeners();
		}

		return result;
	}

	/**
	 * Restores the most recent valid on-disk backup into memory and writes profile configuration
	 * plus a file backup.
	 *
	 * @return true if a backup was loaded
	 */
	public synchronized boolean restoreFromMostRecentFileBackup()
	{
		if (stateStore == null)
		{
			return false;
		}

		Optional<TcgState> restored = stateStore.loadMostRecentFileBackup();
		if (restored.isEmpty())
		{
			return false;
		}

		state = restored.get();
		if (shouldResetDebugTaintedSave())
		{
			log.info("OSRS TCG: file backup had debug mode enabled; resetting collection and economy.");
			resetAll();
			return true;
		}

		if (state.isDebugLogging() && runeliteDeveloperMode)
		{
			log.info("OSRS TCG: file backup had debug mode enabled; keeping collection (developer mode active).");
		}

		boolean strippedDebug = stripDebugProvenanceRowsIfDebugDisabled();
		saveToProfile();
		if (strippedDebug)
		{
			notifyCollectionShareListeners();
		}
		return true;
	}

	/**
	 * Persists the current in-memory state to a validated on-disk backup file without writing profile configuration.
	 *
	 * @return true if the file backup was written
	 */
	public synchronized boolean saveToFileBackup()
	{
		flushRewardTuningDraftBeforeLocking();
		if (stateStore == null)
		{
			return false;
		}

		boolean written = stateStore.saveToFileBackup(state);
		if (written)
		{
			lastFileBackupEpochMs = System.currentTimeMillis();
		}
		return written;
	}

	/**
	 * Writes the current in-memory state to RuneLite profile configuration and a file backup.
	 * Used on logout, plugin unload, collection reset, and manual file-backup restore.
	 */
	public synchronized void saveToProfile()
	{
		flushRewardTuningDraftBeforeLocking();
		if (stateStore == null)
		{
			return;
		}
		stateStore.save(state);
		if (stateStore.saveToFileBackup(state))
		{
			lastFileBackupEpochMs = System.currentTimeMillis();
		}
	}

	/**
	 * Persists RSProfile configuration synchronously, then returns a {@link Future} for the file
	 * backup write. RSProfile keys must be written on the calling thread before
	 * {@link net.runelite.client.config.ConfigManager}'s {@code ClientShutdown} handler runs
	 * {@code sendConfig()}; the returned future is for {@code ClientShutdown#waitFor(Future)}.
	 */
	public Future<?> submitSaveToProfile(Executor executor)
	{
		final TcgState snapshot;
		synchronized (this)
		{
			flushRewardTuningDraftBeforeLocking();
			if (stateStore == null)
			{
				return null;
			}
			stateStore.save(state);
			snapshot = state;
		}

		if (executor == null)
		{
			return null;
		}

		return CompletableFuture.runAsync(() ->
		{
			if (stateStore.saveToFileBackup(snapshot))
			{
				synchronized (this)
				{
					lastFileBackupEpochMs = System.currentTimeMillis();
				}
			}
		}, executor);
	}

	/**
	 * Throttled on-disk file backup (at most once per 5 minutes). Does not write RuneLite profile
	 * configuration; that happens via {@link #saveToProfile()}.
	 */
	public synchronized void save()
	{
		if (stateStore == null)
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastFileBackupEpochMs < FILE_BACKUP_THROTTLE_MS)
		{
			return;
		}

		if (stateStore.saveToFileBackup(state))
		{
			lastFileBackupEpochMs = now;
		}
	}

	/** Invoked after share-relevant collection / pack mutations (interop broadcasts). */
	public void addCollectionChangeListener(Runnable listener)
	{
		if (listener != null)
		{
			collectionChangeListeners.addIfAbsent(listener);
		}
	}

	public void removeCollectionChangeListener(Runnable listener)
	{
		if (listener != null)
		{
			collectionChangeListeners.remove(listener);
		}
	}

	private void notifyCollectionShareListeners()
	{
		for (Runnable notify : collectionChangeListeners)
		{
			try
			{
				notify.run();
			}
			catch (Exception ex)
			{
				log.debug("Collection change listener failed", ex);
			}
		}
	}

	public TcgState getState()
	{
		return state;
	}

	public boolean isDebugLogging()
	{
		return state.isDebugLogging();
	}

	/** Debug chat/log output: plugin debug mode, or RuneLite {@code --developer-mode}. */
	public boolean isDebugChatEnabled()
	{
		return state.isDebugLogging() || runeliteDeveloperMode;
	}

	public synchronized void setDebugLogging(boolean enabled)
	{
		if (state.isDebugLogging() == enabled)
		{
			return;
		}
		state = state.withDebugLogging(enabled);
		if (!enabled)
		{
			stripDebugProvenanceRowsIfDebugDisabled();
		}
		save();
	}

	public synchronized void setPackRevealOverlayScale(double multiplier)
	{
		double clamped = PackRevealZoomUtil.clamp(multiplier);
		if (Double.compare(state.getPackRevealOverlayScale(), clamped) == 0)
		{
			return;
		}
		state = state.withPackRevealOverlayScale(clamped);
		save();
	}

	public synchronized void setAlbumWindowSize(int width, int height)
	{
		Dimension clamped = CollectionAlbumWindowSizeUtil.clamp(width, height);
		if (state.getAlbumWindowWidth() == clamped.width && state.getAlbumWindowHeight() == clamped.height)
		{
			return;
		}
		state = state.withAlbumWindowSize(clamped.width, clamped.height);
		save();
	}

	/**
	 * True once the account has credits, has opened a pack, or owns any card — foil rate and credit multipliers are fixed until reset.
	 */
	public boolean isRewardTuningLocked()
	{
		TcgState s = state;
		if (s.getEconomyState().getCredits() != 0L)
		{
			return true;
		}
		if (s.getEconomyState().getOpenedPacks() != 0L)
		{
			return true;
		}
		return !s.getCollectionState().getOwnedCards().isEmpty();
	}

	public synchronized boolean tryUpdateRewardTuning(RewardTuningState next)
	{
		if (next == null || isRewardTuningLocked())
		{
			return false;
		}
		state = state.withRewardTuning(next);
		save();
		return true;
	}

	public long getCredits()
	{
		return state.getEconomyState().getCredits();
	}

	public void setRewardTuningFlushBeforeCredits(Runnable rewardTuningFlushBeforeCredits)
	{
		this.rewardTuningFlushBeforeCredits = rewardTuningFlushBeforeCredits;
	}

	public synchronized void addCredits(long amount)
	{
		if (amount <= 0)
		{
			return;
		}

		flushRewardTuningDraftBeforeLocking();

		state = state.withCredits(state.getEconomyState().getCredits() + amount);
		save();
	}

	public synchronized boolean spendCredits(long amount)
	{
		if (amount <= 0)
		{
			return true;
		}

		long currentCredits = state.getEconomyState().getCredits();
		if (currentCredits < amount)
		{
			return false;
		}

		state = state.withCredits(currentCredits - amount);
		save();
		return true;
	}

	public synchronized void incrementOpenedPacks()
	{
		flushRewardTuningDraftBeforeLocking();
		state = state.withOpenedPacks(state.getEconomyState().getOpenedPacks() + 1L);
		save();
	}

	public synchronized void addCard(String cardName, boolean foil, int quantity)
	{
		addCard(cardName, foil, quantity, "", System.currentTimeMillis());
	}

	public synchronized void addCard(String cardName, boolean foil, int quantity, String pulledByUsername, long pulledAtEpochMs)
	{
		if (cardName == null || cardName.isEmpty() || quantity <= 0)
		{
			return;
		}

		flushRewardTuningDraftBeforeLocking();

		String by = pulledByUsername == null ? "" : pulledByUsername.trim();
		long at = Math.max(0L, pulledAtEpochMs);
		List<OwnedCardInstance> add = new ArrayList<>();
		for (int i = 0; i < quantity; i++)
		{
			add.add(OwnedCardInstance.createNew(cardName, foil, by, at));
		}
		state = state.withCollection(state.getCollectionState().withInstancesAdded(add));
		save();
		notifyCollectionShareListeners();
	}

	public synchronized void addOwnedCardInstance(OwnedCardInstance instance)
	{
		if (instance == null)
		{
			return;
		}
		flushRewardTuningDraftBeforeLocking();
		state = state.withCollection(state.getCollectionState().withInstanceAdded(instance));
		save();
		notifyCollectionShareListeners();
	}

	/**
	 * Adds one non-foil copy of each distinct catalog card name (including duplicates already owned).
	 *
	 * @return number of instances added
	 */
	public synchronized int addOneOfEachCatalogCard(List<String> catalogCardNames, String pulledByUsername,
		long pulledAtEpochMs)
	{
		if (catalogCardNames == null || catalogCardNames.isEmpty())
		{
			return 0;
		}

		flushRewardTuningDraftBeforeLocking();

		String by = pulledByUsername == null ? "" : pulledByUsername.trim();
		long at = Math.max(0L, pulledAtEpochMs);
		List<OwnedCardInstance> toAdd = new ArrayList<>();
		Set<String> scheduled = new HashSet<>();

		for (String raw : catalogCardNames)
		{
			if (raw == null)
			{
				continue;
			}
			String name = raw.trim();
			if (name.isEmpty() || !scheduled.add(name))
			{
				continue;
			}
			toAdd.add(OwnedCardInstance.createNew(name, false, by, at));
		}

		if (toAdd.isEmpty())
		{
			return 0;
		}

		state = state.withCollection(state.getCollectionState().withInstancesAdded(toAdd));
		save();
		notifyCollectionShareListeners();
		return toAdd.size();
	}

	/** Snapshot of owned quantities before a bulk collection change (e.g. debug complete). */
	public synchronized Map<CardCollectionKey, Integer> copyOwnedCardsSnapshot()
	{
		return new java.util.HashMap<>(state.getCollectionState().getOwnedCards());
	}

	public synchronized void setCollectionInstances(List<OwnedCardInstance> replacement)
	{
		flushRewardTuningDraftBeforeLocking();
		state = state.withCollection(CollectionState.copyOf(replacement == null ? List.of() : replacement));
		save();
		notifyCollectionShareListeners();
	}

	public synchronized boolean toggleCardInstanceLock(String instanceId)
	{
		if (instanceId == null || instanceId.isEmpty())
		{
			return false;
		}
		CollectionState before = state.getCollectionState();
		CollectionState after = before.withInstanceLockToggled(instanceId);
		if (after == before)
		{
			return false;
		}
		state = state.withCollection(after);
		save();
		return true;
	}

	public synchronized boolean applyPackOpenTransaction(long packPrice, List<PackCardResult> pulls, String pullerDisplayName)
	{
		return applyPackOpenTransaction(packPrice, pulls, false, pullerDisplayName);
	}

	/**
	 * @param allowZeroPrice when true, {@code packPrice == 0} is allowed (debug-only free packs). Provenance is tagged
	 * with {@link OwnedCardInstance#DEBUG_PULL_METADATA_PREFIX} when {@code allowZeroPrice} or saved Overview debug
	 * logging is enabled.
	 */
	public synchronized boolean applyPackOpenTransaction(long packPrice, List<PackCardResult> pulls, boolean allowZeroPrice,
		String pullerDisplayName)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return false;
		}
		if (packPrice < 0L)
		{
			return false;
		}
		if (packPrice == 0L && !allowZeroPrice)
		{
			return false;
		}

		flushRewardTuningDraftBeforeLocking();

		long currentCredits = state.getEconomyState().getCredits();
		if (currentCredits < packPrice)
		{
			return false;
		}

		String rawBy = pullerDisplayName == null ? "" : pullerDisplayName.trim();
		boolean tagDebugProvenance = allowZeroPrice || state.isDebugLogging();
		String by = tagDebugProvenance ? OwnedCardInstance.withDebugPullMetadataPrefix(rawBy) : rawBy;
		long now = System.currentTimeMillis();
		List<OwnedCardInstance> pulled = new ArrayList<>();
		for (PackCardResult pull : pulls)
		{
			if (pull == null || pull.getCardName() == null || pull.getCardName().isEmpty())
			{
				continue;
			}
			pulled.add(OwnedCardInstance.createNew(pull.getCardName().trim(), pull.isFoil(), by, now));
		}
		if (pulled.isEmpty())
		{
			return false;
		}

		CollectionState nextColl = state.getCollectionState().withInstancesAdded(pulled);
		state = state
			.withCredits(currentCredits - packPrice)
			.withOpenedPacks(state.getEconomyState().getOpenedPacks() + 1L)
			.withCollection(nextColl);
		save();
		notifyCollectionShareListeners();
		return true;
	}

	public synchronized void resetAll()
	{
		state = TcgState.empty();
		saveToProfile();
		notifyCollectionShareListeners();
	}

	public synchronized boolean removeCardInstance(String instanceId)
	{
		if (instanceId == null || instanceId.isEmpty())
		{
			return false;
		}
		if (state.getCollectionState().findInstanceById(instanceId).isEmpty())
		{
			return false;
		}
		state = state.withCollection(state.getCollectionState().withInstanceRemoved(instanceId));
		save();
		notifyCollectionShareListeners();
		return true;
	}

	/**
	 * Removes {@code quantity} copies of a variant, preferring oldest pulls first (FIFO).
	 *
	 * @return true if at least one copy was removed
	 */
	/**
	 * Oldest-pulled matching copy first (same ordering as {@link #removeCardQuantityFifo}).
	 */
	public synchronized java.util.Optional<OwnedCardInstance> firstInstanceFifo(String cardName, boolean foil)
	{
		if (cardName == null || cardName.isEmpty())
		{
			return java.util.Optional.empty();
		}
		String n = cardName.trim();
		List<OwnedCardInstance> matches = new ArrayList<>();
		for (OwnedCardInstance i : state.getCollectionState().getOwnedInstances())
		{
			if (n.equalsIgnoreCase(i.getCardName()) && foil == i.isFoil())
			{
				matches.add(i);
			}
		}
		if (matches.isEmpty())
		{
			return java.util.Optional.empty();
		}
		matches.sort(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs));
		return java.util.Optional.of(matches.get(0));
	}

	public synchronized boolean removeCardQuantityFifo(String cardName, boolean foil, int quantity)
	{
		if (cardName == null || cardName.isEmpty() || quantity <= 0)
		{
			return false;
		}
		String n = cardName.trim();
		List<OwnedCardInstance> list = new ArrayList<>(state.getCollectionState().getOwnedInstances());
		List<OwnedCardInstance> matches = new ArrayList<>();
		for (OwnedCardInstance i : list)
		{
			if (n.equalsIgnoreCase(i.getCardName()) && foil == i.isFoil())
			{
				matches.add(i);
			}
		}
		if (matches.size() < quantity)
		{
			return false;
		}
		matches.sort(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs));
		for (int r = 0; r < quantity; r++)
		{
			list.remove(matches.get(r));
		}
		state = state.withCollection(CollectionState.copyOf(list));
		save();
		notifyCollectionShareListeners();
		return true;
	}

	private void flushRewardTuningDraftBeforeLocking()
	{
		Runnable flush = rewardTuningFlushBeforeCredits;
		if (flush != null && !isRewardTuningLocked())
		{
			flush.run();
		}
	}

	private boolean shouldResetDebugTaintedSave()
	{
		return state.isDebugLogging() && !runeliteDeveloperMode;
	}

	/**
	 * @return true if the in-memory collection was mutated
	 */
	private boolean stripDebugProvenanceRowsIfDebugDisabled()
	{
		if (state.isDebugLogging())
		{
			return false;
		}
		CollectionState current = state.getCollectionState();
		CollectionState next = current.withoutDebugProvenanceRows();
		if (next == current)
		{
			return false;
		}
		state = state.withCollection(next);
		return true;
	}
}

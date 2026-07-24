package com.osrstcg.service;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.PackCardResult;
import com.osrstcg.model.RewardTuningState;
import com.osrstcg.model.SkillCreditBaseline;
import com.osrstcg.model.TcgState;
import com.osrstcg.persist.TcgBackupProfile;
import com.osrstcg.persist.TcgSaveMetadataEntry;
import com.osrstcg.persist.TcgSaveTrigger;
import com.osrstcg.persist.TcgStateFileBackupStore;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TcgStateService
{
	private final TcgStateStore stateStore;
	private final boolean runeliteDeveloperMode;
	private final Provider<OsrsTcgConfig> config;
	private final Provider<ShopNotificationService> shopNotificationService;
	private final CreditsRateTracker creditsRateTracker;
	private volatile TcgState state = TcgState.empty();
	private Runnable rewardTuningFlushBeforeCredits;
	private final CopyOnWriteArrayList<Runnable> collectionChangeListeners = new CopyOnWriteArrayList<>();

	@Inject
	public TcgStateService(
		TcgStateStore stateStore,
		@Named("developerMode") boolean runeliteDeveloperMode,
		Provider<OsrsTcgConfig> config,
		Provider<ShopNotificationService> shopNotificationService,
		CreditsRateTracker creditsRateTracker)
	{
		this.stateStore = stateStore;
		this.runeliteDeveloperMode = runeliteDeveloperMode;
		this.config = config;
		this.shopNotificationService = shopNotificationService;
		this.creditsRateTracker = creditsRateTracker;
	}

	TcgStateService(TcgState initialState)
	{
		this.stateStore = null;
		this.runeliteDeveloperMode = false;
		this.config = null;
		this.shopNotificationService = null;
		this.creditsRateTracker = null;
		this.state = initialState == null ? TcgState.empty() : initialState;
	}

	/**
	 * Loads persisted state for the current RS profile.
	 */
	public synchronized TcgStateLoadResult load()
	{
		if (stateStore == null)
		{
			return new TcgStateLoadResult(state, TcgStateLoadSource.CONFIG);
		}

		TcgStateLoadResult result = stateStore.load();
		state = result.getState();
		if (shouldResetDebugTaintedSave())
		{
			log.info("OSRS TCG: loaded profile had debug mode enabled; resetting collection and economy.");
			resetAll();
			return new TcgStateLoadResult(
				state,
				result.getSource(),
				result.isConfigLoadFailed(),
				result.isDiskLoadFailed(),
				true);
		}

		if (state.isDebugLogging() && runeliteDeveloperMode)
		{
			log.info("OSRS TCG: loaded profile had debug mode enabled; keeping collection (developer mode active).");
		}

		boolean strippedDebug = stripDebugProvenanceRowsIfDebugDisabled();
		boolean upgradedSkillBaseline = ensureSkillCreditBaselineSchemaField();
		boolean upgradedProfileMeta = ensureProfileMetaSchemaFields();
		if (upgradedSkillBaseline)
		{
			state = state.withSkillCreditBaseline(SkillCreditBaseline.absent());
		}
		if (strippedDebug || upgradedSkillBaseline || upgradedProfileMeta)
		{
			if (strippedDebug)
			{
				saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
				notifyCollectionShareListeners();
			}
			else
			{
				saveCheckpoint(TcgSaveTrigger.MANUAL);
			}
		}

		if (result.getSource() != TcgStateLoadSource.EMPTY)
		{
			writeValidatedLoadToConfig();
		}

		return result;
	}

	/**
	 * Persists the validated in-memory state to RSProfile {@code state}/{@code hash} after load.
	 */
	private void writeValidatedLoadToConfig()
	{
		if (stateStore == null)
		{
			return;
		}
		stateStore.saveConfigOnly(state);
	}

	/**
	 * Restores from {@code tcg.save} (no prefix) or the newest hash snapshot matching {@code prefix}.
	 *
	 * @return true if a save was loaded
	 */
	public synchronized boolean restoreFromDiskSave(Optional<String> hashPrefix)
	{
		if (stateStore == null)
		{
			return false;
		}

		Optional<TcgState> restored;
		if (hashPrefix.isEmpty() || hashPrefix.get().isBlank())
		{
			restored = stateStore.loadMaster();
			if (restored.isEmpty())
			{
				restored = stateStore.loadMostRecentSnapshot();
			}
		}
		else
		{
			restored = stateStore.loadByHashPrefix(hashPrefix.get().trim());
		}

		return applyRestoredDiskState(restored);
	}

	/**
	 * Restores a specific save file ({@code tcg.save} or a hash-named snapshot) from the current profile.
	 */
	public synchronized boolean restoreFromDiskFile(String fileName)
	{
		return restoreFromDiskFile(null, fileName);
	}

	/**
	 * Restores a save from a backups profile directory into the current in-memory / RSProfile state.
	 */
	public synchronized boolean restoreFromDiskFile(String profileDirId, String fileName)
	{
		if (stateStore == null || fileName == null || fileName.isEmpty())
		{
			return false;
		}
		return applyRestoredDiskState(stateStore.loadByFileName(profileDirId, fileName.trim()));
	}

	/** Lists disk saves for the current profile ({@code tcg.save} + snapshots). */
	public synchronized List<TcgSaveMetadataEntry> listDiskSaves()
	{
		return listDiskSaves(null);
	}

	/** Lists disk saves for a backups profile directory id. */
	public synchronized List<TcgSaveMetadataEntry> listDiskSaves(String profileDirId)
	{
		if (stateStore == null)
		{
			return List.of();
		}
		return stateStore.listSaveMetadata(profileDirId);
	}

	public synchronized List<TcgBackupProfile> listBackupProfiles()
	{
		if (stateStore == null)
		{
			return List.of();
		}
		return stateStore.listBackupProfiles();
	}

	public synchronized String currentBackupProfileId()
	{
		if (stateStore == null)
		{
			return TcgStateFileBackupStore.DEFAULT_PROFILE_DIR;
		}
		return stateStore.currentBackupProfileId();
	}

	/** Peeks a save without applying it (for UI stats). */
	public synchronized Optional<TcgState> peekDiskSave(String fileName)
	{
		return peekDiskSave(null, fileName);
	}

	public synchronized Optional<TcgState> peekDiskSave(String profileDirId, String fileName)
	{
		if (stateStore == null || fileName == null || fileName.isEmpty())
		{
			return Optional.empty();
		}
		return stateStore.loadByFileName(profileDirId, fileName.trim());
	}

	private boolean applyRestoredDiskState(Optional<TcgState> restored)
	{
		if (restored.isEmpty())
		{
			return false;
		}

		state = restored.get();
		if (shouldResetDebugTaintedSave())
		{
			log.info("OSRS TCG: disk save had debug mode enabled; resetting collection and economy.");
			resetAll();
			return true;
		}

		if (state.isDebugLogging() && runeliteDeveloperMode)
		{
			log.info("OSRS TCG: disk save had debug mode enabled; keeping collection (developer mode active).");
		}

		boolean strippedDebug = stripDebugProvenanceRowsIfDebugDisabled();
		// Disk restores keep collection/economy from the save, but never its skill XP baselines —
		// CreditAwardService rebases those to live stats for the current profile after restore.
		state = state.withSkillCreditBaseline(SkillCreditBaseline.absent());
		ensureProfileMetaSchemaFields();
		saveCheckpoint(TcgSaveTrigger.LOAD);
		if (strippedDebug)
		{
			notifyCollectionShareListeners();
		}
		return true;
	}

	/** @deprecated use {@link #restoreFromDiskSave(Optional)} */
	@Deprecated
	public synchronized boolean restoreFromMostRecentFileBackup()
	{
		return restoreFromDiskSave(Optional.empty());
	}

	/**
	 * Ensures older profiles that omitted skillCreditBaseline are rewritten on disk.
	 *
	 * @return true if the loaded profile needs a schema placeholder written on next save
	 */
	private boolean ensureSkillCreditBaselineSchemaField()
	{
		SkillCreditBaseline baseline = state.getSkillCreditBaseline();
		if (baseline == null)
		{
			state = state.withSkillCreditBaseline(SkillCreditBaseline.absent());
			return true;
		}
		return baseline.needsSchemaUpgradePersist();
	}

	/**
	 * Stamps schema-5 profile metadata when missing (legacy saves).
	 *
	 * @return true if state was updated and should be persisted
	 */
	private boolean ensureProfileMetaSchemaFields()
	{
		boolean changed = false;
		if (state.getProfileCreatedAtUnix() <= 0L)
		{
			state = state.withProfileCreatedAtUnix(TcgState.currentUnixSeconds());
			changed = true;
		}
		return changed;
	}

	/** Replaces the persisted skill XP baseline in memory (does not save by itself). */
	public synchronized void replaceSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		SkillCreditBaseline next = baseline == null ? SkillCreditBaseline.absent() : baseline;
		if (Objects.equals(state.getSkillCreditBaseline(), next))
		{
			return;
		}
		state = state.withSkillCreditBaseline(next);
	}

	/**
	 * Writes {@code tcg.save} + {@code saves.json} for a card collection change.
	 */
	public synchronized boolean saveMasterOnly(TcgSaveTrigger trigger)
	{
		flushRewardTuningDraftBeforeLocking();
		if (stateStore == null)
		{
			return false;
		}
		return stateStore.saveMasterOnly(state, trigger == null ? TcgSaveTrigger.COLLECTION_CHANGE : trigger);
	}

	/**
	 * Hash snapshot + RSProfile config (no {@code tcg.save}). Used by {@code ::tcg-save} and post-load restore.
	 */
	public synchronized boolean saveCheckpoint(TcgSaveTrigger trigger)
	{
		flushRewardTuningDraftBeforeLocking();
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveCheckpoint(state, trigger == null ? TcgSaveTrigger.MANUAL : trigger);
	}

	/**
	 * {@code tcg.save} + snapshot + config. Used on logout and client shutdown.
	 */
	public synchronized boolean saveFullCheckpoint(TcgSaveTrigger trigger)
	{
		flushRewardTuningDraftBeforeLocking();
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveFullCheckpoint(state, trigger == null ? TcgSaveTrigger.LOGOUT : trigger);
	}

	/** @deprecated use {@link #saveCheckpoint(TcgSaveTrigger)} */
	@Deprecated
	public synchronized boolean saveToFileBackup()
	{
		return saveCheckpoint(TcgSaveTrigger.MANUAL);
	}

	/** @deprecated use {@link #saveFullCheckpoint(TcgSaveTrigger)} */
	@Deprecated
	public synchronized void saveToProfile()
	{
		saveFullCheckpoint(TcgSaveTrigger.LOGOUT);
	}

	/**
	 * Non-collection persistence: keeps state in memory only until the next checkpoint.
	 */
	public synchronized void save()
	{
		// Intentionally no disk/config write (credits, UI prefs, etc.).
	}

	/** Invoked after share-relevant collection / pack mutations (web sync, interop broadcasts). */
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

	/** Whether Overview debug mode is active (console tracing for credit awards). */
	public boolean isDebugTracingActive()
	{
		return state.isDebugLogging();
	}

	/** In-game debug chat: controlled only by the plugin settings debug-messages toggle. */
	public boolean isDebugChatEnabled()
	{
		return config != null && config.get().debugMessages();
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
			if (stripDebugProvenanceRowsIfDebugDisabled())
			{
				saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
				notifyCollectionShareListeners();
				return;
			}
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
		notifyCollectionShareListeners();
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

		long creditsBefore = state.getEconomyState().getCredits();
		long creditsAfter = creditsBefore + amount;
		long gainedAfter = state.getTotalCreditsGained() + amount;
		state = state.withCredits(creditsAfter).withTotalCreditsGained(gainedAfter);
		save();

		if (creditsRateTracker != null)
		{
			creditsRateTracker.recordCreditGain(amount);
		}

		if (shopNotificationService != null)
		{
			shopNotificationService.get().onCreditsIncreased(creditsBefore, creditsAfter);
		}
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
		notifyCollectionShareListeners();
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
		notifyCollectionShareListeners();
		return true;
	}

	public synchronized void resetAll()
	{
		state = TcgState.empty();
		saveFullCheckpoint(TcgSaveTrigger.RESET);
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
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
		saveMasterOnly(TcgSaveTrigger.COLLECTION_CHANGE);
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

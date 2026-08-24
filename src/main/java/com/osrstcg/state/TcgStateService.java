package com.osrstcg.state;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.persist.TcgSaveMetadataEntry;
import com.osrstcg.persist.TcgSaveTrigger;
import com.osrstcg.persist.TcgStateLoadResult;
import com.osrstcg.persist.TcgStateLoadSource;
import com.osrstcg.persist.TcgStateStore;
import com.osrstcg.util.PackRevealZoomUtil;
import com.google.inject.name.Named;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.credit.CreditsRateTracker;
import com.osrstcg.notify.CreditNotificationService;

@Singleton
@Slf4j
public class TcgStateService
{
	private final TcgStateStore stateStore;
	private final boolean runeliteDeveloperMode;
	private final Provider<OsrsTcgConfig> config;
	private final Provider<CreditNotificationService> creditNotificationService;
	private final CreditsRateTracker creditsRateTracker;
	private volatile TcgState state = TcgState.empty();
	/**
	 * In-memory cache of cloud collection overview (beta-excluded). Filled from {@code /me/stats}
	 * / inbox stats; not persisted locally.
	 */
	private volatile CloudSidebarCollectionStats cloudCollectionStats;
	private final OptimisticCreditBuffer optimistic = new OptimisticCreditBuffer();
	private final TcgStateNotifier notifier = new TcgStateNotifier();

	@Inject
	public TcgStateService(
		TcgStateStore stateStore,
		@Named("developerMode") boolean runeliteDeveloperMode,
		Provider<OsrsTcgConfig> config,
		Provider<CreditNotificationService> creditNotificationService,
		CreditsRateTracker creditsRateTracker)
	{
		this.stateStore = stateStore;
		this.runeliteDeveloperMode = runeliteDeveloperMode;
		this.config = config;
		this.creditNotificationService = creditNotificationService;
		this.creditsRateTracker = creditsRateTracker;
	}

	/** Test / local harness constructor (no disk store). */
	public TcgStateService(TcgState initialState)
	{
		this.stateStore = null;
		this.runeliteDeveloperMode = false;
		this.config = null;
		this.creditNotificationService = null;
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
			return new TcgStateLoadResult(state, TcgStateLoadSource.EMPTY);
		}

		TcgStateLoadResult result = stateStore.load();
		state = result.getState();
		optimistic.clear();
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
		ensureProfileMetaSchemaFields();
		if (upgradedSkillBaseline)
		{
			state = state.withSkillCreditBaseline(SkillCreditBaseline.absent());
		}
		if (strippedDebug)
		{
			// Memory-only until logout / unload / client close - no mid-session disk write.
			notifyCollectionMutated();
		}
		// Schema placeholder upgrades stay in memory; next full checkpoint persists them.

		return result;
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
	 * Stamps schema-5 profile metadata when missing.
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
	 * Hash snapshot only. Used by {@code ::tcg-save} (explicit manual backup).
	 * Routine collection changes do not write disk - see {@link #saveFullCheckpoint}.
	 */
	public synchronized boolean saveCheckpoint(TcgSaveTrigger trigger)
	{
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveCheckpoint(state, trigger == null ? TcgSaveTrigger.MANUAL : trigger);
	}

	/** Lists disk saves for the current profile ({@code tcg.save} + snapshots). */
	public synchronized List<TcgSaveMetadataEntry> listDiskSaves()
	{
		if (stateStore == null)
		{
			return List.of();
		}
		return stateStore.listSaveMetadata();
	}

	/** True when the current profile backups folder has save files on disk. */
	public synchronized boolean hasDiskSaves()
	{
		return stateStore != null && stateStore.hasSaveFiles();
	}

	/** Peeks a save without applying it (for UI stats). */
	public synchronized Optional<TcgState> peekDiskSave(String fileName)
	{
		if (stateStore == null || fileName == null || fileName.isEmpty())
		{
			return Optional.empty();
		}
		return stateStore.loadByFileName(fileName.trim());
	}

	/**
	 * Applies a current-profile disk save into memory before cloud migrate upload.
	 * Does not write a restore checkpoint - the migrate path uploads this state next.
	 * <p>
	 * Refuses debug-mode saves outside RuneLite developer mode (does not wipe memory).
	 * The save picker peeks the raw file, so a silent reset here used to look like a
	 * successful pick while {@code migrateLocalCollection} then skipped the upload.
	 */
	public synchronized boolean applyDiskSaveForMigrate(String fileName)
	{
		if (stateStore == null || fileName == null || fileName.isEmpty())
		{
			return false;
		}
		Optional<TcgState> restored = stateStore.loadByFileName(fileName.trim());
		if (restored.isEmpty())
		{
			return false;
		}

		TcgState candidate = restored.get();
		if (candidate.isDebugLogging() && !runeliteDeveloperMode)
		{
			log.warn("OSRS TCG: refusing migrate of debug-mode save outside developer mode ({})",
				fileName.trim());
			return false;
		}

		state = candidate;
		optimistic.clear();

		if (state.isDebugLogging() && runeliteDeveloperMode)
		{
			log.info("OSRS TCG: migrate save had debug mode enabled; keeping collection (developer mode active).");
		}

		stripDebugProvenanceRowsIfDebugDisabled();
		// Keep collection/economy from the save, but clear skill XP baselines -
		// CreditAwardService rebases those to live stats for the current profile.
		state = state.withSkillCreditBaseline(SkillCreditBaseline.absent());
		ensureProfileMetaSchemaFields();
		notifyCollectionMutated();
		return true;
	}

	/**
	 * {@code tcg.save} + hash snapshot. Intended for logout, plugin unload, and client close only
	 * (plus rare intentional resets / {@code ::tcg-save} full backups).
	 */
	public synchronized boolean saveFullCheckpoint(TcgSaveTrigger trigger)
	{
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveFullCheckpoint(state, trigger == null ? TcgSaveTrigger.LOGOUT : trigger);
	}

	/**
	 * Non-collection persistence: keeps state in memory only until the next checkpoint.
	 */
	public synchronized void save()
	{
		// Intentionally no disk/config write (credits, UI prefs, etc.).
	}

	/**
	 * Broad state-change listener (collection, economy, sidebar stats, ranks).
	 * Used by the plugin panel for EDT refresh.
	 */
	public void addCollectionChangeListener(Runnable listener)
	{
		notifier.addStateChangeListener(listener);
	}

	public void removeCollectionChangeListener(Runnable listener)
	{
		notifier.removeStateChangeListener(listener);
	}

	/** Fires only when owned card instances change (not economy/stats-only updates). */
	public void addOwnedCollectionListener(Runnable listener)
	{
		notifier.addOwnedCollectionListener(listener);
	}

	public void removeOwnedCollectionListener(Runnable listener)
	{
		notifier.removeOwnedCollectionListener(listener);
	}

	private void notifyStateChangeListeners()
	{
		notifier.notifyStateChangeListeners();
	}

	/** Collection instances changed - notify UI and owned-names interop. */
	private void notifyCollectionMutated()
	{
		notifier.notifyCollectionMutated();
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
				notifyCollectionMutated();
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

	public long getCredits()
	{
		return getAuthoritativeCredits() + optimistic.get();
	}

	/** Last known server-settled credits (excludes unacked optimistic gains). */
	public synchronized long getAuthoritativeCredits()
	{
		return state.getEconomyState().getCredits();
	}

	/** Unacked optimistic gains awaiting attest acceptance. */
	public synchronized long getPendingOptimisticCredits()
	{
		return optimistic.get();
	}

	/**
	 * Replace local economy display values from cloud authority (does not touch collection instances).
	 * Preserves unacked optimistic credits so stale polls cannot wipe optimistic gains.
	 */
	public synchronized void replaceCloudEconomyCache(long credits, int openedPacks, long totalCreditsGained)
	{
		long pending = optimistic.get();
		state = TcgCloudStateApplier.applyEconomy(state, credits, openedPacks, totalCreditsGained);
		save();
		log.debug("Cloud economy apply: serverCredits={} pendingOptimistic={} displayCredits={}",
			credits, pending, getCredits());
		notifyStateChangeListeners();
	}

	/**
	 * Full replace of collection + economy from structured {@code GET /me/state}, preserving local UI prefs
	 * and unacked optimistic credits.
	 */
	public synchronized void replaceFromCloudState(
		CollectionState collection,
		EconomyState economy,
		long totalCreditsGained,
		long cloudRevision,
		String cloudStateHash,
		CloudSidebarCollectionStats sidebarStats)
	{
		EconomyState nextEconomy = economy == null ? EconomyState.empty() : economy;
		long pending = optimistic.get();
		state = TcgCloudStateApplier.applyFull(
			state, collection, economy, totalCreditsGained, cloudRevision, cloudStateHash);
		if (sidebarStats != null)
		{
			this.cloudCollectionStats = sidebarStats;
		}
		// Memory-only - disk backup waits for logout / unload / client close.
		log.debug("Cloud state apply: serverCredits={} pendingOptimistic={} displayCredits={}",
			nextEconomy.getCredits(), pending, getCredits());
		notifyCollectionMutated();
	}

	/**
	 * Update cloud sync markers after a local mutation that already matches the server
	 * so inbox reconcile does not pull a full {@code /me/state}.
	 */
	public synchronized void applyCloudSyncMarkers(long revision, String stateHash)
	{
		long nextRevision = Math.max(0L, revision);
		String nextHash = stateHash == null ? "" : stateHash.trim();
		if (state.getCloudRevision() == nextRevision
			&& (state.getCloudStateHash() == null ? "" : state.getCloudStateHash()).equalsIgnoreCase(nextHash))
		{
			return;
		}
		state = state.withCloudSyncMarkers(nextRevision, nextHash);
		save();
	}

	/**
	 * Cached hiscores ranks from the last pack-open that included them (persisted across sessions).
	 * @return length-6 ranks, or null if none stored yet
	 */
	public int[] getSidebarRanks()
	{
		return state.getSidebarRanks();
	}

	/**
	 * Persist sidebar ranks from a pack-open response (replaces prior cache).
	 * @param ranks length-6 ranks ({@code 0} = unranked), or null to clear
	 */
	public synchronized void replaceSidebarRanks(int[] ranks)
	{
		int[] next = TcgState.copyRanks(ranks);
		int[] cur = state.getSidebarRanks();
		if (java.util.Arrays.equals(cur, next))
		{
			return;
		}
		state = state.withSidebarRanks(next);
		save();
		notifyStateChangeListeners();
	}

	/**
	 * Cache beta-excluded collection overview from cloud {@code /me/stats} (or inbox {@code stats}).
	 */
	public synchronized void replaceCloudCollectionStatsCache(CloudSidebarCollectionStats stats)
	{
		this.cloudCollectionStats = stats;
		notifyStateChangeListeners();
	}

	/** @return cloud collection overview if a stats payload has been applied this session; else {@code null} */
	public CloudSidebarCollectionStats getCloudCollectionStats()
	{
		return cloudCollectionStats;
	}

	public synchronized void clearCloudCollectionStatsCache()
	{
		this.cloudCollectionStats = null;
	}

	/** Drop unacked optimistic credit display. */
	public synchronized void clearOptimisticCredits()
	{
		optimistic.clear();
	}

	/**
	 * Wipe local collection/economy (keeping UI prefs) and clear cloud sync markers so the next
	 * {@code /me/state} pull always replaces local progress.
	 */
	public synchronized void resetProgressForCloudResync()
	{
		optimistic.clear();
		cloudCollectionStats = null;
		TcgState s = state;
		state = new TcgState(
			TcgState.CURRENT_SCHEMA_VERSION,
			EconomyState.empty(),
			CollectionState.empty(),
			s.isDebugLogging(),
			s.getPackRevealOverlayScale(),
			SkillCreditBaseline.absent(),
			0L,
			s.getProfileCreatedAtUnix(),
			s.getProfileSavedAtUnix(),
			0L,
			"",
			null);
		saveFullCheckpoint(TcgSaveTrigger.RESET);
		notifyCollectionMutated();
	}

	/**
	 * Optimistic credit gain while waiting for server attest ack. Not persisted; display-only until settled.
	 */
	public synchronized void addOptimisticCredits(long amount)
	{
		if (amount <= 0)
		{
			return;
		}
		long creditsBefore = getCredits();
		optimistic.add(amount);
		long creditsAfter = getCredits();

		if (creditsRateTracker != null)
		{
			creditsRateTracker.recordCreditGain(amount);
		}

		if (creditNotificationService != null)
		{
			creditNotificationService.get().onCreditsIncreased(creditsBefore, creditsAfter);
		}
	}

	/**
	 * Clears unacked optimistic credits after a successful attest batch (accepted portion).
	 * Clamps at zero so a later older-batch response cannot erase newer pending.
	 */
	public synchronized void clearOptimisticCredits(long amount)
	{
		if (amount <= 0 || optimistic.get() <= 0)
		{
			return;
		}
		optimistic.clearAmount(amount);
	}

	public synchronized void addCredits(long amount)
	{
		if (amount <= 0)
		{
			return;
		}

		long creditsBefore = state.getEconomyState().getCredits();
		long creditsAfter = creditsBefore + amount;
		long gainedAfter = state.getTotalCreditsGained() + amount;
		state = state.withCredits(creditsAfter).withTotalCreditsGained(gainedAfter);
		save();

		if (creditsRateTracker != null)
		{
			creditsRateTracker.recordCreditGain(amount);
		}

		if (creditNotificationService != null)
		{
			creditNotificationService.get().onCreditsIncreased(creditsBefore, creditsAfter);
		}
	}

	public synchronized boolean spendCredits(long amount)
	{
		if (amount <= 0)
		{
			return true;
		}

		if (getCredits() < amount)
		{
			return false;
		}

		long fromPending = optimistic.consumeForSpend(amount);
		long fromAuth = amount - fromPending;
		if (fromAuth > 0)
		{
			state = state.withCredits(state.getEconomyState().getCredits() - fromAuth);
			save();
		}
		return true;
	}

	public synchronized void addCard(String cardName, boolean foil, int quantity, String pulledByUsername, long pulledAtEpochMs)
	{
		if (cardName == null || cardName.isEmpty() || quantity <= 0)
		{
			return;
		}


		String by = pulledByUsername == null ? "" : pulledByUsername.trim();
		long at = Math.max(0L, pulledAtEpochMs);
		List<OwnedCardInstance> add = new ArrayList<>();
		for (int i = 0; i < quantity; i++)
		{
			add.add(OwnedCardInstance.createNew(cardName, foil, by, at));
		}
		state = state.withCollection(state.getCollectionState().withInstancesAdded(add));
		notifyCollectionMutated();
	}

	/** Batch-add instances in memory. Disk backup waits for logout/unload/close. */
	public synchronized void addOwnedCardInstances(List<OwnedCardInstance> instances)
	{
		if (instances == null || instances.isEmpty())
		{
			return;
		}
		state = state.withCollection(state.getCollectionState().withInstancesAdded(instances));
		notifyCollectionMutated();
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
		notifyCollectionMutated();
		return toAdd.size();
	}

	/** Snapshot of owned quantities before a bulk collection change. */
	public synchronized Map<CardCollectionKey, Integer> copyOwnedCardsSnapshot()
	{
		return new java.util.HashMap<>(state.getCollectionState().getOwnedCards());
	}

	public synchronized void setCollectionInstances(List<OwnedCardInstance> replacement)
	{
		state = state.withCollection(CollectionState.copyOf(replacement == null ? List.of() : replacement));
		notifyCollectionMutated();
	}

	public synchronized void resetAll()
	{
		optimistic.clear();
		state = TcgState.empty();
		saveFullCheckpoint(TcgSaveTrigger.RESET);
		notifyCollectionMutated();
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

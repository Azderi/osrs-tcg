package com.osrstcg.state;

import com.osrstcg.persist.TcgSaveTrigger;
import com.osrstcg.persist.TcgStateLoadResult;
import com.osrstcg.persist.TcgStateLoadSource;
import com.osrstcg.persist.TcgStateStore;
import com.osrstcg.util.PackRevealZoomUtil;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.credit.CreditsRateTracker;
import com.osrstcg.notify.CreditNotificationService;
import com.osrstcg.OsrsTcgConfig;

/**
 * Single owner of the live {@link TcgState} for the plugin session: exposes mutation methods that
 * replace the state and notify listeners, and delegates persistence to {@link TcgStateStore}.
 * All mutating methods are {@code synchronized}; the plain getter reads the volatile reference
 * without locking so any thread can observe the current snapshot cheaply. Also layers an
 * {@link OptimisticCreditBuffer} on top of the persisted credit balance so UI can show credits
 * gained before the server confirms them.
 */
@Singleton
@Slf4j
public class TcgStateService
{
	private final TcgStateStore stateStore;
	private final Provider<CreditNotificationService> creditNotificationService;
	private final CreditsRateTracker creditsRateTracker;
	private final Provider<OsrsTcgConfig> config;
	private volatile TcgState state = TcgState.empty();
	private volatile CloudSidebarCollectionStats cloudCollectionStats;
	private volatile String cloudCollectionHash = "";
	private volatile String cloudGroupKey;
	private final OptimisticCreditBuffer optimistic = new OptimisticCreditBuffer();
	private final TcgStateNotifier notifier = new TcgStateNotifier();

	@Inject
	public TcgStateService(
		TcgStateStore stateStore,
		Provider<CreditNotificationService> creditNotificationService,
		CreditsRateTracker creditsRateTracker,
		Provider<OsrsTcgConfig> config)
	{
		this.stateStore = stateStore;
		this.creditNotificationService = creditNotificationService;
		this.creditsRateTracker = creditsRateTracker;
		this.config = config;
	}

	/** Test/standalone constructor: no store, no notifications; starts from the given state (or {@code empty()}). */
	public TcgStateService(TcgState initialState)
	{
		this.stateStore = null;
		this.creditNotificationService = null;
		this.creditsRateTracker = null;
		this.config = null;
		this.state = initialState == null ? TcgState.empty() : initialState;
	}

	/**
	 * Loads state from {@link #stateStore}, replacing the in-memory state and clearing optimistic
	 * credits, then applies any pending schema-upgrade fixups. Returns an {@link TcgStateLoadSource#EMPTY}
	 * result with the current in-memory state if this service was constructed without a store.
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
		boolean upgradedSkillBaseline = ensureSkillBaselineSchema();
		ensureProfileMetaSchemaFields();
		if (upgradedSkillBaseline)
		{
			state = state.withSkillCreditBaseline(SkillCreditBaseline.absent());
		}

		return result;
	}

	/** Ensures the loaded state has a non-null skill credit baseline, defaulting to {@code absent()}. */
	private boolean ensureSkillBaselineSchema()
	{
		SkillCreditBaseline baseline = state.getSkillCreditBaseline();
		if (baseline == null)
		{
			state = state.withSkillCreditBaseline(SkillCreditBaseline.absent());
			return true;
		}
		return baseline.needsSchemaUpgradePersist();
	}

	/** Backfills {@code profileCreatedAtUnix} to now for profiles saved before that field existed. */
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

	/** Replaces the skill credit baseline; a no-op if it already equals {@code baseline}. */
	public synchronized void replaceSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		SkillCreditBaseline next = baseline == null ? SkillCreditBaseline.absent() : baseline;
		if (Objects.equals(state.getSkillCreditBaseline(), next))
		{
			return;
		}
		state = state.withSkillCreditBaseline(next);
	}

	/** Stamps the profile-saved timestamp and delegates an incremental checkpoint save to the store. */
	public synchronized boolean saveCheckpoint(TcgSaveTrigger trigger)
	{
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveCheckpoint(state, trigger == null ? TcgSaveTrigger.MANUAL : trigger);
	}

	/** Stamps the profile-saved timestamp and delegates a full checkpoint save to the store. */
	public synchronized boolean saveFullCheckpoint(TcgSaveTrigger trigger)
	{
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveFullCheckpoint(state, trigger == null ? TcgSaveTrigger.LOGOUT : trigger);
	}

	/** Registers a listener invoked on any state change (economy, collection, ranks, etc). */
	public void addCollectionChangeListener(Runnable listener)
	{
		notifier.addStateChangeListener(listener);
	}

	/** Unregisters a listener added via {@link #addCollectionChangeListener}. */
	public void removeCollectionChangeListener(Runnable listener)
	{
		notifier.removeStateChangeListener(listener);
	}

	/** Registers a listener invoked specifically when the owned card collection is mutated. */
	public void addOwnedCollectionListener(Runnable listener)
	{
		notifier.addOwnedCollectionListener(listener);
	}

	/** Unregisters a listener added via {@link #addOwnedCollectionListener}. */
	public void removeOwnedCollectionListener(Runnable listener)
	{
		notifier.removeOwnedCollectionListener(listener);
	}

	/** Fires all general state-change listeners. */
	private void notifyStateChangeListeners()
	{
		notifier.notifyStateChangeListeners();
	}

	/** Fires the collection-mutated notification path (general listeners plus owned-collection listeners). */
	private void notifyCollectionMutated()
	{
		notifier.notifyCollectionMutated();
	}

	/** Returns the current state snapshot. */
	public TcgState getState()
	{
		return state;
	}

	/** Sets the pack reveal overlay zoom, clamped to the valid range; a no-op if unchanged. */
	public synchronized void setPackRevealOverlayScale(double multiplier)
	{
		double clamped = PackRevealZoomUtil.clamp(multiplier);
		if (Double.compare(state.getPackRevealOverlayScale(), clamped) == 0)
		{
			return;
		}
		state = state.withPackRevealOverlayScale(clamped);
	}

	/** Returns the display credit balance: persisted credits plus any pending optimistic credits. */
	public long getCredits()
	{
		return getAuthoritativeCredits() + optimistic.get();
	}

	/** Returns the persisted credit balance, excluding any optimistic/unconfirmed credits. */
	public synchronized long getAuthoritativeCredits()
	{
		return state.getEconomyState().getCredits();
	}

	/** Returns the amount of credits currently shown optimistically but not yet confirmed by the server. */
	public synchronized long getPendingOptimisticCredits()
	{
		return optimistic.get();
	}

	/** Returns whether debug chat messages are enabled in config (false if no config is available). */
	public boolean isDebugChatEnabled()
	{
		return config != null && config.get().debugMessages();
	}

	/**
	 * Applies a server-confirmed economy snapshot (credits, opened packs, lifetime total) without
	 * touching the collection, then notifies state-change listeners.
	 */
	public synchronized void replaceCloudEconomyCache(long credits, int openedPacks, long totalCreditsGained)
	{
		long pending = optimistic.get();
		state = TcgCloudStateApplier.applyEconomy(state, credits, openedPacks, totalCreditsGained);
		log.debug("Cloud economy apply: serverCredits={} pendingOptimistic={} displayCredits={}",
			credits, pending, getCredits());
		notifyStateChangeListeners();
	}

	/**
	 * Replaces the full state (collection and economy) from a cloud sync response, updates the
	 * cached sidebar stats and collection hash, and notifies collection-mutated listeners.
	 */
	public synchronized void replaceFromCloudState(
		CollectionState collection,
		EconomyState economy,
		long totalCreditsGained,
		long cloudRevision,
		String cloudStateHash,
		String cloudCollectionHash,
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
		this.cloudCollectionHash = cloudCollectionHash == null ? "" : cloudCollectionHash.trim();
		log.debug("Cloud state apply: serverCredits={} pendingOptimistic={} displayCredits={}",
			nextEconomy.getCredits(), pending, getCredits());
		notifyCollectionMutated();
	}

	/** Updates the cloud revision/hash markers if they differ from the current ones (case-insensitive hash compare). */
	public synchronized void applyCloudSyncMarkers(long revision, String stateHash)
	{
		long nextRevision = Math.max(0L, revision);
		String nextHash = stateHash == null ? "" : stateHash.trim();
		if (state.getCloudRevision() == nextRevision
			&& state.getCloudStateHash().equalsIgnoreCase(nextHash))
		{
			return;
		}
		state = state.withCloudSyncMarkers(nextRevision, nextHash);
	}

	/** Returns the current sidebar rank order, or null if unset. */
	public int[] getSidebarRanks()
	{
		return state.getSidebarRanks();
	}

	/** Validates and replaces the sidebar ranks; a no-op if unchanged, otherwise notifies state-change listeners. */
	public synchronized void replaceSidebarRanks(int[] ranks)
	{
		int[] next = TcgState.copyRanks(ranks);
		int[] cur = state.getSidebarRanks();
		if (java.util.Arrays.equals(cur, next))
		{
			return;
		}
		state = state.withSidebarRanks(next);
		notifyStateChangeListeners();
	}

	/** Replaces the cached cloud sidebar collection stats and notifies state-change listeners. */
	public synchronized void replaceCollectionStatsCache(CloudSidebarCollectionStats stats)
	{
		this.cloudCollectionStats = stats;
		notifyStateChangeListeners();
	}

	/** Returns the cached cloud collection hash (never null). */
	public String getCloudCollectionHash()
	{
		String hash = cloudCollectionHash;
		return hash == null ? "" : hash;
	}

	/** Returns the cached cloud sidebar collection stats, or null if none cached. */
	public CloudSidebarCollectionStats getCloudCollectionStats()
	{
		return cloudCollectionStats;
	}

	/** Clears the cached cloud sidebar collection stats. */
	public synchronized void clearCollectionStatsCache()
	{
		this.cloudCollectionStats = null;
	}

	/** Sets the cloud group key (trimmed; blank/null clears it); a no-op if unchanged, otherwise notifies owned-collection listeners. */
	public synchronized void replaceCloudGroupKey(String groupKey)
	{
		String next = groupKey == null || groupKey.isBlank() ? null : groupKey.trim();
		if (Objects.equals(cloudGroupKey, next))
		{
			return;
		}
		cloudGroupKey = next;
		notifier.notifyOwnedCollectionListeners();
	}

	/** Returns the current cloud group key, or null if not in a cloud group. */
	public String getCloudGroupKey()
	{
		return cloudGroupKey;
	}

	/** Clears the cloud group key. */
	public synchronized void clearCloudGroupKey()
	{
		replaceCloudGroupKey(null);
	}

	/** Clears any pending optimistic (unconfirmed) credits. */
	public synchronized void clearOptimisticCredits()
	{
		optimistic.clear();
	}

	/**
	 * Adds an optimistic credit gain (shown immediately, ahead of server confirmation), records it
	 * for the credits-per-hour rate tracker, and fires the credit-increase notification. A no-op for
	 * non-positive amounts.
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

	/** Clears a specific amount of pending optimistic credits (e.g. once the server confirms that portion). */
	public synchronized void clearOptimisticCredits(long amount)
	{
		if (amount <= 0 || optimistic.get() <= 0)
		{
			return;
		}
		optimistic.clearAmount(amount);
	}

	/** Appends newly-pulled card instances to the collection and notifies collection-mutated listeners. */
	public synchronized void addOwnedCardInstances(List<OwnedCardInstance> instances)
	{
		if (instances == null || instances.isEmpty())
		{
			return;
		}
		state = state.withCollection(state.getCollectionState().withInstancesAdded(instances));
		notifyCollectionMutated();
	}

	/** Replaces the entire owned card collection wholesale and notifies collection-mutated listeners. */
	public synchronized void setCollectionInstances(List<OwnedCardInstance> replacement)
	{
		state = state.withCollection(CollectionState.copyOf(replacement == null ? List.of() : replacement));
		notifyCollectionMutated();
	}

	/** Resets to a fresh empty state, clears optimistic credits, persists a full checkpoint, and notifies listeners. */
	public synchronized void resetAll()
	{
		optimistic.clear();
		state = TcgState.empty();
		saveFullCheckpoint(TcgSaveTrigger.RESET);
		notifyCollectionMutated();
	}
}

package com.osrstcg.state;

import com.osrstcg.persist.TcgSaveTrigger;
import com.osrstcg.persist.TcgStateLoadResult;
import com.osrstcg.persist.TcgStateLoadSource;
import com.osrstcg.persist.TcgStateStore;
import com.osrstcg.util.PackRevealZoomUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.credit.CreditsRateTracker;
import com.osrstcg.notify.CreditNotificationService;
import com.osrstcg.OsrsTcgConfig;

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

	public TcgStateService(TcgState initialState)
	{
		this.stateStore = null;
		this.creditNotificationService = null;
		this.creditsRateTracker = null;
		this.config = null;
		this.state = initialState == null ? TcgState.empty() : initialState;
	}

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
				result.isDiskLoadFailed(),
				true);
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
			notifyCollectionMutated();
		}

		return result;
	}

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

	public synchronized void replaceSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		SkillCreditBaseline next = baseline == null ? SkillCreditBaseline.absent() : baseline;
		if (Objects.equals(state.getSkillCreditBaseline(), next))
		{
			return;
		}
		state = state.withSkillCreditBaseline(next);
	}

	public synchronized boolean saveCheckpoint(TcgSaveTrigger trigger)
	{
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveCheckpoint(state, trigger == null ? TcgSaveTrigger.MANUAL : trigger);
	}

	public synchronized boolean saveFullCheckpoint(TcgSaveTrigger trigger)
	{
		if (stateStore == null)
		{
			return false;
		}
		state = state.withProfileSavedAtUnix(TcgState.currentUnixSeconds());
		return stateStore.saveFullCheckpoint(state, trigger == null ? TcgSaveTrigger.LOGOUT : trigger);
	}

	public void addCollectionChangeListener(Runnable listener)
	{
		notifier.addStateChangeListener(listener);
	}

	public void removeCollectionChangeListener(Runnable listener)
	{
		notifier.removeStateChangeListener(listener);
	}

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
	}

	public synchronized void setPackRevealOverlayScale(double multiplier)
	{
		double clamped = PackRevealZoomUtil.clamp(multiplier);
		if (Double.compare(state.getPackRevealOverlayScale(), clamped) == 0)
		{
			return;
		}
		state = state.withPackRevealOverlayScale(clamped);
	}

	public long getCredits()
	{
		return getAuthoritativeCredits() + optimistic.get();
	}

	public synchronized long getAuthoritativeCredits()
	{
		return state.getEconomyState().getCredits();
	}

	public synchronized long getPendingOptimisticCredits()
	{
		return optimistic.get();
	}

	public boolean isDebugChatEnabled()
	{
		return config != null && config.get().debugMessages();
	}

	public synchronized void replaceCloudEconomyCache(long credits, int openedPacks, long totalCreditsGained)
	{
		long pending = optimistic.get();
		state = TcgCloudStateApplier.applyEconomy(state, credits, openedPacks, totalCreditsGained);
		log.debug("Cloud economy apply: serverCredits={} pendingOptimistic={} displayCredits={}",
			credits, pending, getCredits());
		notifyStateChangeListeners();
	}

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

	public int[] getSidebarRanks()
	{
		return state.getSidebarRanks();
	}

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

	public synchronized void replaceCloudCollectionStatsCache(CloudSidebarCollectionStats stats)
	{
		this.cloudCollectionStats = stats;
		notifyStateChangeListeners();
	}

	public String getCloudCollectionHash()
	{
		String hash = cloudCollectionHash;
		return hash == null ? "" : hash;
	}

	public CloudSidebarCollectionStats getCloudCollectionStats()
	{
		return cloudCollectionStats;
	}

	public synchronized void clearCloudCollectionStatsCache()
	{
		this.cloudCollectionStats = null;
	}

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

	public String getCloudGroupKey()
	{
		return cloudGroupKey;
	}

	public synchronized void clearCloudGroupKey()
	{
		replaceCloudGroupKey(null);
	}

	public synchronized void clearOptimisticCredits()
	{
		optimistic.clear();
	}

	public synchronized void resetProgressForCloudResync()
	{
		optimistic.clear();
		cloudCollectionStats = null;
		cloudGroupKey = null;
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

	public synchronized void clearOptimisticCredits(long amount)
	{
		if (amount <= 0 || optimistic.get() <= 0)
		{
			return;
		}
		optimistic.clearAmount(amount);
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

	public synchronized void addOwnedCardInstances(List<OwnedCardInstance> instances)
	{
		if (instances == null || instances.isEmpty())
		{
			return;
		}
		state = state.withCollection(state.getCollectionState().withInstancesAdded(instances));
		notifyCollectionMutated();
	}

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
		return state.isDebugLogging();
	}

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

package com.runelitetcg.service;

import com.runelitetcg.model.CollectionState;
import com.runelitetcg.model.OwnedCardInstance;
import com.runelitetcg.model.PackCardResult;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.model.TcgState;
import com.runelitetcg.persist.TcgStateStore;
import com.runelitetcg.util.CollectionAlbumWindowSizeUtil;
import com.runelitetcg.util.PackRevealZoomUtil;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TcgStateService
{
	private final TcgStateStore stateStore;
	private volatile TcgState state = TcgState.empty();
	private Runnable rewardTuningFlushBeforeCredits;

	@Inject
	public TcgStateService(TcgStateStore stateStore)
	{
		this.stateStore = stateStore;
	}

	TcgStateService(TcgState initialState)
	{
		this.stateStore = null;
		this.state = initialState == null ? TcgState.empty() : initialState;
	}

	public synchronized void load()
	{
		if (stateStore == null)
		{
			return;
		}
		state = stateStore.load();
		if (stripDebugProvenanceRowsIfDebugDisabled())
		{
			save();
		}
	}

	public synchronized void save()
	{
		if (stateStore == null)
		{
			return;
		}
		stateStore.save(state);
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
	}

	public synchronized void setCollectionInstances(List<OwnedCardInstance> replacement)
	{
		flushRewardTuningDraftBeforeLocking();
		state = state.withCollection(CollectionState.copyOf(replacement == null ? List.of() : replacement));
		save();
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
		return true;
	}

	public synchronized void resetAll()
	{
		state = TcgState.empty();
		save();
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

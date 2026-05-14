package com.runelitetcg.service;

import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.CollectionState;
import com.runelitetcg.model.PackCardResult;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.model.TcgState;
import com.runelitetcg.persist.TcgStateStore;
import com.runelitetcg.util.PackRevealZoomUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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
		if (cardName == null || cardName.isEmpty() || quantity <= 0)
		{
			return;
		}

		long now = System.currentTimeMillis();
		updateCollection(collection ->
		{
			CardCollectionKey key = new CardCollectionKey(cardName, foil);
			int existing = collection.getOrDefault(key, 0);
			collection.put(key, existing + quantity);
			return collection;
		}, timestamps ->
		{
			CardCollectionKey key = new CardCollectionKey(cardName, foil);
			timestamps.put(key, now);
			return timestamps;
		});
	}

	public synchronized void updateCollection(UnaryOperator<Map<CardCollectionKey, Integer>> mutation)
	{
		updateCollection(mutation, timestamps -> timestamps);
	}

	public synchronized void updateCollection(
		UnaryOperator<Map<CardCollectionKey, Integer>> mutation,
		UnaryOperator<Map<CardCollectionKey, Long>> timestampMutation)
	{
		flushRewardTuningDraftBeforeLocking();

		Map<CardCollectionKey, Integer> copy = new HashMap<>(state.getCollectionState().getOwnedCards());
		Map<CardCollectionKey, Integer> result = mutation.apply(copy);
		Map<CardCollectionKey, Integer> normalized = result == null ? copy : result;
		Map<CardCollectionKey, Long> tsCopy = new HashMap<>(state.getCollectionState().getLastObtainedMap());
		Map<CardCollectionKey, Long> tsResult = timestampMutation == null ? tsCopy : timestampMutation.apply(tsCopy);
		Map<CardCollectionKey, Long> normalizedTs = tsResult == null ? tsCopy : tsResult;
		state = state.withCollection(new CollectionState(normalized, normalizedTs));
		save();
	}

	public synchronized boolean applyPackOpenTransaction(long packPrice, List<PackCardResult> pulls)
	{
		return applyPackOpenTransaction(packPrice, pulls, false);
	}

	/**
	 * @param allowZeroPrice when true, {@code packPrice == 0} is allowed (debug-only free packs).
	 */
	public synchronized boolean applyPackOpenTransaction(long packPrice, List<PackCardResult> pulls, boolean allowZeroPrice)
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

		Map<CardCollectionKey, Integer> nextCollection = new HashMap<>(state.getCollectionState().getOwnedCards());
		Map<CardCollectionKey, Long> nextTimestamps = new HashMap<>(state.getCollectionState().getLastObtainedMap());
		long now = System.currentTimeMillis();
		for (PackCardResult pull : pulls)
		{
			if (pull == null || pull.getCardName() == null || pull.getCardName().isEmpty())
			{
				continue;
			}

			CardCollectionKey key = new CardCollectionKey(pull.getCardName(), pull.isFoil());
			nextCollection.put(key, nextCollection.getOrDefault(key, 0) + 1);
			nextTimestamps.put(key, now);
		}

		state = state
			.withCredits(currentCredits - packPrice)
			.withOpenedPacks(state.getEconomyState().getOpenedPacks() + 1L)
			.withCollection(new CollectionState(nextCollection, nextTimestamps));
		save();
		return true;
	}

	public synchronized void resetAll()
	{
		state = TcgState.empty();
		save();
	}

	/**
	 * Removes up to {@code quantity} copies of a specific card variant. Drops timestamp entries when quantity reaches zero.
	 *
	 * @return true if at least one copy was removed
	 */
	public synchronized boolean removeCardQuantity(String cardName, boolean foil, int quantity)
	{
		if (cardName == null || cardName.isEmpty() || quantity <= 0)
		{
			return false;
		}
		CardCollectionKey key = new CardCollectionKey(cardName, foil);
		Map<CardCollectionKey, Integer> copy = new HashMap<>(state.getCollectionState().getOwnedCards());
		int cur = copy.getOrDefault(key, 0);
		if (cur < quantity)
		{
			return false;
		}
		int next = cur - quantity;
		if (next <= 0)
		{
			copy.remove(key);
		}
		else
		{
			copy.put(key, next);
		}
		Map<CardCollectionKey, Long> tsCopy = new HashMap<>(state.getCollectionState().getLastObtainedMap());
		if (next <= 0)
		{
			tsCopy.remove(key);
		}
		state = state.withCollection(new CollectionState(copy, tsCopy));
		save();
		return true;
	}

	/**
	 * Persists sidebar draft foil / multipliers via {@link #rewardTuningFlushBeforeCredits} before mutating state in a way
	 * that can {@link #isRewardTuningLocked() lock} tuning without an {@link #addCredits(long)} call.
	 */
	private void flushRewardTuningDraftBeforeLocking()
	{
		Runnable flush = rewardTuningFlushBeforeCredits;
		if (flush != null && !isRewardTuningLocked())
		{
			flush.run();
		}
	}
}

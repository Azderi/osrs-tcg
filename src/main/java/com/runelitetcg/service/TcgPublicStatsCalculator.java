package com.runelitetcg.service;

import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.TcgPublicStats;
import com.runelitetcg.model.TcgState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Computes the same collection overview numbers as the plugin panel (roll pool, owned map, score rules).
 */
@Singleton
public class TcgPublicStatsCalculator
{
	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;

	@Inject
	public TcgPublicStatsCalculator(TcgStateService stateService, CardDatabase cardDatabase)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
	}

	public TcgPublicStats computeLive()
	{
		Map<CardCollectionKey, Integer> owned;
		long openedPacks;
		synchronized (stateService)
		{
			TcgState s = stateService.getState();
			owned = new HashMap<>(s.getCollectionState().getOwnedCards());
			openedPacks = s.getEconomyState().getOpenedPacks();
		}
		return compute(owned, openedPacks);
	}

	private TcgPublicStats compute(Map<CardCollectionKey, Integer> owned, long openedPacks)
	{
		List<CardDefinition> all = cardDatabase.getCards();
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(all);

		Set<String> rollPoolNames = new HashSet<>();
		for (CardDefinition c : rollPool)
		{
			if (c != null && c.getName() != null)
			{
				rollPoolNames.add(c.getName());
			}
		}

		int uniqueOwned = (int) owned.keySet().stream()
			.filter(k -> k.getCardName() != null && rollPoolNames.contains(k.getCardName()))
			.count();
		int totalCardsOwned = owned.entrySet().stream()
			.filter(e -> e.getKey().getCardName() != null && rollPoolNames.contains(e.getKey().getCardName()))
			.mapToInt(e -> e.getValue() == null ? 0 : e.getValue())
			.sum();
		int totalCardPool = rollPool.size();
		double completionPct = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueOwned) / totalCardPool;

		Set<String> collectedNames = collectedNamesFromOwned(owned);
		Map<String, CardDefinition> defByLower = new HashMap<>();
		for (CardDefinition c : all)
		{
			if (c != null && c.getName() != null)
			{
				defByLower.putIfAbsent(c.getName().toLowerCase(Locale.ROOT), c);
			}
		}
		long collectionScore = 0L;
		for (String cardName : collectedNames)
		{
			if (cardName == null || !rollPoolNames.contains(cardName))
			{
				continue;
			}
			CardDefinition def = defByLower.get(cardName.toLowerCase(Locale.ROOT));
			if (def == null)
			{
				continue;
			}
			boolean hasFoil = hasFoilOwned(owned, cardName);
			collectionScore += hasFoil ? RarityMath.foilAdjustedScoreRounded(def) : Math.round(RarityMath.score(def));
		}

		return new TcgPublicStats(collectionScore, completionPct, uniqueOwned, totalCardPool, openedPacks, totalCardsOwned);
	}

	private static Set<String> collectedNamesFromOwned(Map<CardCollectionKey, Integer> owned)
	{
		Map<String, Integer> ownedQtyByName = new HashMap<>();
		for (Map.Entry<CardCollectionKey, Integer> entry : owned.entrySet())
		{
			String cardName = entry.getKey().getCardName();
			if (cardName == null)
			{
				continue;
			}
			int qty = entry.getValue() == null ? 0 : entry.getValue();
			ownedQtyByName.merge(cardName, qty, Integer::sum);
		}
		Set<String> collectedNames = new HashSet<>();
		for (Map.Entry<String, Integer> entry : ownedQtyByName.entrySet())
		{
			if (entry.getValue() != null && entry.getValue() > 0)
			{
				collectedNames.add(entry.getKey());
			}
		}
		return collectedNames;
	}

	private static boolean hasFoilOwned(Map<CardCollectionKey, Integer> owned, String cardName)
	{
		if (cardName == null)
		{
			return false;
		}
		Integer n = owned.get(new CardCollectionKey(cardName, true));
		return n != null && n > 0;
	}
}

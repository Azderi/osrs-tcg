package com.osrstcg.ui.shop;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.CollectionSetCompletionUtil;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.ui.collection.CollectionListModel;
import com.osrstcg.ui.layout.PackCloseSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShopProgress
{
	private static final Object ELIGIBLE_LOCK = new Object();
	private static List<CardDefinition> cachedAllCards;
	private static List<CardDefinition> cachedRollPool;
	private static final Map<String, Set<String>> eligibleByPackKey = new java.util.HashMap<>();

	private ShopProgress()
	{
	}

	public static Set<String> foilCollectedNamesFromOwned(Map<CardCollectionKey, Integer> owned)
	{
		Set<String> foilNames = new HashSet<>();
		for (Map.Entry<CardCollectionKey, Integer> entry : owned.entrySet())
		{
			CardCollectionKey key = entry.getKey();
			if (key == null || !key.isFoil())
			{
				continue;
			}
			String cardName = key.getCardName();
			Integer qty = entry.getValue();
			if (cardName == null || qty == null || qty <= 0)
			{
				continue;
			}
			String trimmed = cardName.trim();
			if (!trimmed.isEmpty())
			{
				foilNames.add(trimmed);
			}
		}
		return foilNames;
	}

	public static int[] ownedTotal(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool,
		Map<CardCollectionKey, Integer> owned)
	{
		Set<String> collectedNames = CollectionSetCompletionUtil.collectedNamesFromOwned(owned);
		Set<String> foilCollectedNames = foilCollectedNamesFromOwned(owned);
		Set<String> eligible = eligibleNames(booster, allCards, rollPool);
		int total = eligible.size();
		int own = 0;
		int foilOwn = 0;
		for (String name : eligible)
		{
			if (collectedNames.contains(name))
			{
				own++;
			}
			if (foilCollectedNames.contains(name))
			{
				foilOwn++;
			}
		}
		return new int[] { own, foilOwn, total };
	}

	private static Set<String> eligibleNames(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
	{
		synchronized (ELIGIBLE_LOCK)
		{
			if (allCards != cachedAllCards || rollPool != cachedRollPool)
			{
				eligibleByPackKey.clear();
				cachedAllCards = allCards;
				cachedRollPool = rollPool;
			}
			String key = packEligibleKey(booster);
			Set<String> cached = eligibleByPackKey.get(key);
			if (cached != null)
			{
				return cached;
			}
			Set<String> eligible = computeEligible(booster, allCards, rollPool);
			eligibleByPackKey.put(key, eligible);
			return eligible;
		}
	}

	private static String packEligibleKey(BoosterPackDefinition booster)
	{
		if (booster == null)
		{
			return "";
		}
		String id = booster.getId();
		if (id != null && !id.isBlank())
		{
			return id;
		}
		return String.valueOf(booster.getCategoryFilters());
	}

	private static Set<String> computeEligible(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
	{
		return CollectionListModel.eligibleNamesForPack(booster, allCards, rollPool);
	}

	public static List<BoosterShopRow> computeRows(
		PackCloseSnapshot snap,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool,
		List<BoosterPackDefinition> boosters)
	{
		List<BoosterShopRow> out = new ArrayList<>(boosters.size());
		for (BoosterPackDefinition booster : boosters)
		{
			if (booster == null)
			{
				continue;
			}
			int[] p = ownedTotal(booster, allCards, rollPool, snap.owned);
			out.add(new BoosterShopRow(booster, p[0], p[1], p[2]));
		}
		return out;
	}
}

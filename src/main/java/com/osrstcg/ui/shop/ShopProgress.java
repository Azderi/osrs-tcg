package com.osrstcg.ui.shop;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.CollectionSetCompletionUtil;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.ui.collection.CollectionListModel;
import com.osrstcg.ui.layout.PackCloseSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * Computes per-booster set-completion progress (owned / foil-owned / total) for the shop and collection
 * tabs, caching each pack's eligible-name set keyed on the current card/roll-pool list identity.
 */
public final class ShopProgress
{
	private static final Object ELIGIBLE_LOCK = new Object();
	private static List<CardDefinition> cachedAllCards;
	private static List<CardDefinition> cachedRollPool;
	private static final Map<String, Set<String>> eligibleByPackKey = new java.util.HashMap<>();

	private ShopProgress()
	{
	}
/** Names of cards owned as a foil (positive quantity, non-blank name), from an owned-card count map. */
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
/**
	 * Counts owned/foil-owned/total for a booster's eligible card set.
	 * @return {@code {own, foilOwn, total}}
	 */
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
/**
	 * Looks up (or computes and caches) the eligible-name set for a booster. The cache is invalidated
	 * whenever the {@code allCards}/{@code rollPool} list identity changes.
	 */
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
			Set<String> eligible = CollectionListModel.eligibleNamesForPack(booster, allCards, rollPool);
			eligibleByPackKey.put(key, eligible);
			return eligible;
		}
	}
/** Cache key for a booster: its id, or its category filters when the id is missing/blank. */
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
/** Builds one {@link BoosterShopRow} per non-null booster, with progress computed against {@code snap.owned}. */
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
/**
	 * Display names of pack collections that became fully owned between {@code ownedBefore} and
	 * {@code ownedAfter} (same eligibility as {@link #ownedTotal}). Every booster is checked on its
	 * own eligible set (shared {@code collectionKey} values are not skipped); announcement labels
	 * are deduped by display name.
	 */
	public static List<String> newlyCompletedCollections(
		Map<CardCollectionKey, Integer> ownedBefore,
		Map<CardCollectionKey, Integer> ownedAfter,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool,
		List<BoosterPackDefinition> boosters)
	{
		if (boosters == null || boosters.isEmpty())
		{
			return Collections.emptyList();
		}
		Set<String> names = new LinkedHashSet<>();
		for (BoosterPackDefinition booster : boosters)
		{
			if (booster == null)
			{
				continue;
			}
			String key = booster.getCollectionKey();
			if (key == null || key.isBlank())
			{
				continue;
			}
			int[] before = ownedTotal(booster, allCards, rollPool, ownedBefore);
			int[] after = ownedTotal(booster, allCards, rollPool, ownedAfter);
			int total = before[2];
			if (total <= 0 || before[0] >= total || after[0] < total)
			{
				continue;
			}
			String display = booster.collectionDisplayName();
			if (display != null && !display.isBlank())
			{
				names.add(display);
			}
		}
		return new ArrayList<>(names);
	}
}

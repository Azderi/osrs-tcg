package com.osrstcg.service;

import com.osrstcg.data.CardDefinition;
import com.osrstcg.model.CardCollectionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects when a primary-category "set" (all roll-pool cards sharing that album category) becomes fully collected.
 * Logic matches overview / album name collection (positive total quantity per card name, any foil mix).
 */
public final class CollectionSetCompletionUtil
{
	private CollectionSetCompletionUtil()
	{
	}

	/** Distinct card names with combined foil + non-foil quantity ≥ 1 (same rules as the sidebar overview). */
	public static Set<String> collectedNamesFromOwned(Map<CardCollectionKey, Integer> owned)
	{
		if (owned == null || owned.isEmpty())
		{
			return Collections.emptySet();
		}
		Map<String, Integer> ownedQtyByName = new HashMap<>();
		for (Map.Entry<CardCollectionKey, Integer> entry : owned.entrySet())
		{
			CardCollectionKey key = entry.getKey();
			if (key == null || key.getCardName() == null)
			{
				continue;
			}
			int qty = entry.getValue() == null ? 0 : entry.getValue();
			ownedQtyByName.merge(key.getCardName(), qty, Integer::sum);
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

	/**
	 * Primary category display labels for which every card in {@code rollPool} with that category is collected.
	 */
	public static Set<String> completedPrimaryCategoryNames(Map<CardCollectionKey, Integer> owned,
		List<CardDefinition> rollPool)
	{
		if (rollPool == null || rollPool.isEmpty())
		{
			return Collections.emptySet();
		}
		Set<String> collected = collectedNamesFromOwned(owned);
		Map<String, List<CardDefinition>> byCategory = new HashMap<>();
		for (CardDefinition c : rollPool)
		{
			if (c == null || c.getName() == null || c.getName().trim().isEmpty())
			{
				continue;
			}
			String cat = c.getPrimaryCategory();
			byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(c);
		}
		Set<String> done = new HashSet<>();
		outer:
		for (Map.Entry<String, List<CardDefinition>> e : byCategory.entrySet())
		{
			for (CardDefinition c : e.getValue())
			{
				if (!collected.contains(c.getName()))
				{
					continue outer;
				}
			}
			done.add(e.getKey());
		}
		return done;
	}

	public static List<String> newlyCompletedPrimaryCategories(Map<CardCollectionKey, Integer> ownedBefore,
		Map<CardCollectionKey, Integer> ownedAfter, List<CardDefinition> rollPool)
	{
		Set<String> after = completedPrimaryCategoryNames(ownedAfter, rollPool);
		Set<String> before = completedPrimaryCategoryNames(ownedBefore, rollPool);
		after.removeAll(before);
		return new ArrayList<>(after);
	}
}

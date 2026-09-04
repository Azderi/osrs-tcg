package com.osrstcg.catalog;

import com.osrstcg.state.CardCollectionKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * Derives category/collection-set completion status from an owned-cards map: which card names are
 * owned, and which primary categories in the roll pool are fully collected.
 */
public final class CollectionSetCompletionUtil
{
	private CollectionSetCompletionUtil()
	{
	}
/** Card names with at least one owned copy (summed across normal/foil variants), from {@code owned} quantities keyed by {@link CardCollectionKey}. */
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
/** Whether {@code owned} has at least one foil copy of {@code cardName}. */
	public static boolean hasFoilOwned(Map<CardCollectionKey, Integer> owned, String cardName)
	{
		if (owned == null || cardName == null)
		{
			return false;
		}
		Integer n = owned.get(new CardCollectionKey(cardName, true));
		return n != null && n > 0;
	}
/** Primary category names from {@code rollPool} where every card in that category is owned per {@code owned}. */
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
/** Categories completed in {@code ownedAfter} but not in {@code ownedBefore}, i.e. completed by the change between the two snapshots. */
	public static List<String> newlyCompletedCategories(Map<CardCollectionKey, Integer> ownedBefore,
		Map<CardCollectionKey, Integer> ownedAfter, List<CardDefinition> rollPool)
	{
		Set<String> after = completedPrimaryCategoryNames(ownedAfter, rollPool);
		Set<String> before = completedPrimaryCategoryNames(ownedBefore, rollPool);
		after.removeAll(before);
		return new ArrayList<>(after);
	}
}

package com.osrstcg.catalog;

import com.osrstcg.state.CardCollectionKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
/**
 * Derives owned-card name sets and foil ownership from an owned-cards map.
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
}

package com.runelitetcg.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class CollectionState
{
	private final Map<CardCollectionKey, Integer> ownedCards;
	private final Map<CardCollectionKey, Long> lastObtainedAt;

	public CollectionState(Map<CardCollectionKey, Integer> ownedCards)
	{
		this(ownedCards, Collections.emptyMap());
	}

	public CollectionState(Map<CardCollectionKey, Integer> ownedCards, Map<CardCollectionKey, Long> lastObtainedAt)
	{
		this.ownedCards = new HashMap<>();
		this.lastObtainedAt = new HashMap<>();
		if (ownedCards != null)
		{
			for (Map.Entry<CardCollectionKey, Integer> entry : ownedCards.entrySet())
			{
				if (entry.getKey() == null)
				{
					continue;
				}

				int quantity = entry.getValue() == null ? 0 : entry.getValue();
				if (quantity > 0)
				{
					CardCollectionKey key = entry.getKey();
					this.ownedCards.put(key, quantity);
					long ts = 0L;
					if (lastObtainedAt != null && lastObtainedAt.get(key) != null)
					{
						ts = Math.max(0L, lastObtainedAt.get(key));
					}
					this.lastObtainedAt.put(key, ts);
				}
			}
		}
	}

	public static CollectionState empty()
	{
		return new CollectionState(Collections.emptyMap());
	}

	public Map<CardCollectionKey, Integer> getOwnedCards()
	{
		return Collections.unmodifiableMap(ownedCards);
	}

	public long getLastObtainedAt(CardCollectionKey key)
	{
		if (key == null)
		{
			return 0L;
		}
		return lastObtainedAt.getOrDefault(key, 0L);
	}

	public Map<CardCollectionKey, Long> getLastObtainedMap()
	{
		return Collections.unmodifiableMap(lastObtainedAt);
	}
}

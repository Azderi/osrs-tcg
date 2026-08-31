package com.osrstcg.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CollectionState
{
	private final List<OwnedCardInstance> instances;
	private final Map<CardCollectionKey, Integer> ownedCards;

	private CollectionState(List<OwnedCardInstance> instances)
	{
		List<OwnedCardInstance> copy = new ArrayList<>();
		if (instances != null)
		{
			for (OwnedCardInstance i : instances)
			{
				if (i != null && i.getCardName() != null && !i.getCardName().trim().isEmpty())
				{
					copy.add(i);
				}
			}
		}
		this.instances = Collections.unmodifiableList(copy);
		this.ownedCards = Collections.unmodifiableMap(aggregateQuantities(copy, false));
	}

	private CollectionState(List<OwnedCardInstance> instances, Map<CardCollectionKey, Integer> ownedCards)
	{
		this.instances = Collections.unmodifiableList(instances);
		this.ownedCards = Collections.unmodifiableMap(ownedCards);
	}

	public static CollectionState empty()
	{
		return new CollectionState(List.of());
	}

	public static CollectionState copyOf(List<OwnedCardInstance> instances)
	{
		return new CollectionState(instances);
	}

	public List<OwnedCardInstance> getOwnedInstances()
	{
		return instances;
	}

	public Map<CardCollectionKey, Integer> getOwnedCards()
	{
		return ownedCards;
	}

	public Map<CardCollectionKey, Integer> getOwnedCardsExcludingBeta()
	{
		return aggregateQuantities(instances, true);
	}

	public CollectionState withInstancesAdded(List<OwnedCardInstance> toAdd)
	{
		if (toAdd == null || toAdd.isEmpty())
		{
			return this;
		}
		List<OwnedCardInstance> filtered = new ArrayList<>(toAdd.size());
		for (OwnedCardInstance i : toAdd)
		{
			if (i != null && i.getCardName() != null && !i.getCardName().trim().isEmpty())
			{
				filtered.add(i);
			}
		}
		if (filtered.isEmpty())
		{
			return this;
		}
		List<OwnedCardInstance> next = new ArrayList<>(instances.size() + filtered.size());
		next.addAll(instances);
		next.addAll(filtered);
		Map<CardCollectionKey, Integer> nextOwned = new HashMap<>(ownedCards);
		for (OwnedCardInstance i : filtered)
		{
			nextOwned.merge(new CardCollectionKey(i.getCardName(), i.isFoil()), 1, Integer::sum);
		}
		return new CollectionState(next, nextOwned);
	}

	private static Map<CardCollectionKey, Integer> aggregateQuantities(
		List<OwnedCardInstance> list,
		boolean excludeBeta)
	{
		Map<CardCollectionKey, Integer> map = new HashMap<>();
		for (OwnedCardInstance i : list)
		{
			if (i == null || (excludeBeta && i.isBeta()))
			{
				continue;
			}
			CardCollectionKey key = new CardCollectionKey(i.getCardName(), i.isFoil());
			map.merge(key, 1, Integer::sum);
		}
		return map;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof CollectionState))
		{
			return false;
		}
		CollectionState that = (CollectionState) o;
		return instances.equals(that.instances);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(instances);
	}
}

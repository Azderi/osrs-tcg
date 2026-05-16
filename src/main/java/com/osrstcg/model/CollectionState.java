package com.osrstcg.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collection backed by individual {@link OwnedCardInstance} rows (quantities are aggregated for overview / album counts).
 */
public final class CollectionState
{
	private final List<OwnedCardInstance> instances;
	private final Map<CardCollectionKey, Integer> ownedCards;
	private final Map<CardCollectionKey, Long> lastObtainedAt;

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
		this.ownedCards = Collections.unmodifiableMap(aggregateQuantities(copy));
		this.lastObtainedAt = Collections.unmodifiableMap(maxPulledAt(copy));
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

	public List<OwnedCardInstance> instancesForCardName(String cardName)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return List.of();
		}
		String n = cardName.trim();
		List<OwnedCardInstance> out = new ArrayList<>();
		for (OwnedCardInstance i : instances)
		{
			if (n.equals(i.getCardName()))
			{
				out.add(i);
			}
		}
		return Collections.unmodifiableList(out);
	}

	public java.util.Optional<OwnedCardInstance> findInstanceById(String instanceId)
	{
		if (instanceId == null || instanceId.isEmpty())
		{
			return java.util.Optional.empty();
		}
		for (OwnedCardInstance i : instances)
		{
			if (instanceId.equals(i.getInstanceId()))
			{
				return java.util.Optional.of(i);
			}
		}
		return java.util.Optional.empty();
	}

	public Map<CardCollectionKey, Integer> getOwnedCards()
	{
		return ownedCards;
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
		return lastObtainedAt;
	}

	public CollectionState withInstanceAdded(OwnedCardInstance instance)
	{
		if (instance == null)
		{
			return this;
		}
		List<OwnedCardInstance> next = new ArrayList<>(instances);
		next.add(instance);
		return new CollectionState(next);
	}

	public CollectionState withInstancesAdded(List<OwnedCardInstance> toAdd)
	{
		if (toAdd == null || toAdd.isEmpty())
		{
			return this;
		}
		List<OwnedCardInstance> next = new ArrayList<>(instances);
		next.addAll(toAdd);
		return new CollectionState(next);
	}

	public CollectionState withInstanceRemoved(String instanceId)
	{
		if (instanceId == null || instanceId.isEmpty())
		{
			return this;
		}
		List<OwnedCardInstance> next = new ArrayList<>(instances);
		next.removeIf(i -> instanceId.equals(i.getInstanceId()));
		return new CollectionState(next);
	}

	/**
	 * Returns a collection with instances removed whose provenance is debug-marked ({@link OwnedCardInstance#hasDebugPullMetadata}).
	 */
	public CollectionState withoutDebugProvenanceRows()
	{
		List<OwnedCardInstance> next = new ArrayList<>();
		for (OwnedCardInstance i : instances)
		{
			if (!OwnedCardInstance.hasDebugPullMetadata(i.getPulledByUsername()))
			{
				next.add(i);
			}
		}
		if (next.size() == instances.size())
		{
			return this;
		}
		return new CollectionState(next);
	}

	public CollectionState withInstances(List<OwnedCardInstance> replacement)
	{
		return new CollectionState(replacement);
	}

	private static Map<CardCollectionKey, Integer> aggregateQuantities(List<OwnedCardInstance> list)
	{
		Map<CardCollectionKey, Integer> map = new HashMap<>();
		for (OwnedCardInstance i : list)
		{
			CardCollectionKey key = new CardCollectionKey(i.getCardName(), i.isFoil());
			map.merge(key, 1, Integer::sum);
		}
		return map;
	}

	private static Map<CardCollectionKey, Long> maxPulledAt(List<OwnedCardInstance> list)
	{
		Map<CardCollectionKey, Long> map = new HashMap<>();
		for (OwnedCardInstance i : list)
		{
			CardCollectionKey key = new CardCollectionKey(i.getCardName(), i.isFoil());
			map.merge(key, i.getPulledAtEpochMs(), Math::max);
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

package com.osrstcg.service;

import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.OwnedCardInstance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks one unlocked spare copy per card variant (name + foil) when the player owns duplicates.
 * Normals: 2+ owned copies with at least one unlocked spare.
 * Foils: only when 2+ unlocked foil copies of that card are available (not locked / already offered).
 */
public final class MissingDuplicateOfferPlanner
{
	private MissingDuplicateOfferPlanner()
	{
	}

	/**
	 * For each (cardName, foil) variant with offerable duplicates, returns one unlocked instance that is not already
	 * offered, preferring the oldest pull (FIFO). Foil variants require multiple unlocked foils.
	 */
	public static List<OwnedCardInstance> selectOfferableDuplicates(
		List<OwnedCardInstance> owned,
		Set<String> alreadyOfferedInstanceIds)
	{
		if (owned == null || owned.isEmpty())
		{
			return List.of();
		}
		Set<String> offered = alreadyOfferedInstanceIds == null
			? Set.of()
			: alreadyOfferedInstanceIds;

		Map<CardCollectionKey, List<OwnedCardInstance>> byVariant = new HashMap<>();
		for (OwnedCardInstance inst : owned)
		{
			if (inst == null || inst.getCardName() == null || inst.getCardName().trim().isEmpty())
			{
				continue;
			}
			CardCollectionKey key = new CardCollectionKey(inst.getCardName().trim(), inst.isFoil());
			byVariant.computeIfAbsent(key, k -> new ArrayList<>()).add(inst);
		}

		List<OwnedCardInstance> out = new ArrayList<>();
		for (Map.Entry<CardCollectionKey, List<OwnedCardInstance>> entry : byVariant.entrySet())
		{
			boolean foilVariant = entry.getKey().isFoil();
			List<OwnedCardInstance> copies = entry.getValue();
			if (!foilVariant && copies.size() < 2)
			{
				continue;
			}

			List<OwnedCardInstance> unlocked = new ArrayList<>();
			for (OwnedCardInstance inst : copies)
			{
				if (inst.isLocked())
				{
					continue;
				}
				String id = inst.getInstanceId();
				if (id != null && offered.contains(id))
				{
					continue;
				}
				unlocked.add(inst);
			}
			if (foilVariant)
			{
				// Only offer a foil when multiple unlocked foils of this card remain.
				if (unlocked.size() < 2)
				{
					continue;
				}
			}
			else if (unlocked.isEmpty())
			{
				continue;
			}
			unlocked.sort(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs));
			out.add(unlocked.get(0));
		}
		out.sort(Comparator
			.comparing(OwnedCardInstance::getCardName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(OwnedCardInstance::isFoil));
		return out;
	}

	/**
	 * Keeps unlocked candidates whose variant key appears in {@code missingKeys}.
	 * Locked instances are never included. Foils are dropped when an unlocked normal duplicate
	 * spare of the same card name is available to offer.
	 */
	public static List<OwnedCardInstance> filterToMissing(
		List<OwnedCardInstance> candidates,
		Set<CardCollectionKey> missingKeys,
		List<OwnedCardInstance> owned,
		Set<String> alreadyOfferedInstanceIds)
	{
		if (candidates == null || candidates.isEmpty() || missingKeys == null || missingKeys.isEmpty())
		{
			return List.of();
		}
		List<OwnedCardInstance> out = new ArrayList<>();
		for (OwnedCardInstance inst : candidates)
		{
			if (inst == null || inst.isLocked())
			{
				continue;
			}
			CardCollectionKey key = new CardCollectionKey(
				inst.getCardName() == null ? "" : inst.getCardName().trim(),
				inst.isFoil());
			if (missingKeys.contains(key))
			{
				out.add(inst);
			}
		}
		return preferNormalOverFoil(out, owned, alreadyOfferedInstanceIds);
	}

	/**
	 * Drops foil copies when an unlocked normal duplicate spare of the same card name is available
	 * (2+ owned normals with at least one unlocked, not already offered). Locked candidates are never kept.
	 */
	public static List<OwnedCardInstance> preferNormalOverFoil(
		List<OwnedCardInstance> candidates,
		List<OwnedCardInstance> owned,
		Set<String> alreadyOfferedInstanceIds)
	{
		if (candidates == null || candidates.isEmpty())
		{
			return List.of();
		}
		Set<String> namesWithUnlockedNormalSpare = new HashSet<>();
		for (OwnedCardInstance spare : selectOfferableDuplicates(owned, alreadyOfferedInstanceIds))
		{
			if (spare != null && !spare.isFoil() && !spare.isLocked())
			{
				String name = spare.getCardName() == null ? "" : spare.getCardName().trim();
				if (!name.isEmpty())
				{
					namesWithUnlockedNormalSpare.add(name);
				}
			}
		}
		List<OwnedCardInstance> out = new ArrayList<>(candidates.size());
		for (OwnedCardInstance inst : candidates)
		{
			if (inst == null || inst.isLocked())
			{
				continue;
			}
			if (inst.isFoil())
			{
				String name = inst.getCardName() == null ? "" : inst.getCardName().trim();
				if (namesWithUnlockedNormalSpare.contains(name))
				{
					continue;
				}
			}
			out.add(inst);
		}
		return out;
	}

	/**
	 * From a queried variant list, returns those the local collection does not own (count 0).
	 */
	public static List<CardCollectionKey> variantsMissingFromCollection(
		List<CardCollectionKey> queried,
		Map<CardCollectionKey, Integer> ownedCounts)
	{
		if (queried == null || queried.isEmpty())
		{
			return List.of();
		}
		Map<CardCollectionKey, Integer> owned = ownedCounts == null ? Map.of() : ownedCounts;
		Set<CardCollectionKey> seen = new HashSet<>();
		List<CardCollectionKey> missing = new ArrayList<>();
		for (CardCollectionKey key : queried)
		{
			if (key == null || key.getCardName() == null || key.getCardName().isEmpty())
			{
				continue;
			}
			if (!seen.add(key))
			{
				continue;
			}
			if (owned.getOrDefault(key, 0) <= 0)
			{
				missing.add(key);
			}
		}
		return missing;
	}
}

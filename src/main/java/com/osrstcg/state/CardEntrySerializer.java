package com.osrstcg.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds and expands {@link CardEntry} rows for profile persistence and web share payloads. */
public final class CardEntrySerializer
{
	private CardEntrySerializer()
	{
	}

	public static List<CardEntry> buildProfileEntries(List<OwnedCardInstance> instances)
	{
		return buildEntries(instances, true, false);
	}

	public static List<OwnedCardInstance> expandToInstances(List<CardEntry> entries)
	{
		List<OwnedCardInstance> rows = new ArrayList<>();
		if (entries == null)
		{
			return rows;
		}
		for (CardEntry entry : entries)
		{
			if (entry == null || entry.cardName == null || entry.cardName.trim().isEmpty() || entry.variants == null)
			{
				continue;
			}
			String cardName = entry.cardName.trim();
			for (CardVariant variant : entry.variants)
			{
				if (variant == null)
				{
					continue;
				}
				String by = variant.pulledBy == null ? "" : variant.pulledBy;
				long at = variant.pulledAt == null || variant.pulledAt <= 0L ? 0L : variant.pulledAt;
				int quantity = variant.quantity == null ? 1 : Math.max(0, variant.quantity);
				if (quantity <= 0)
				{
					continue;
				}
				boolean legacyLockedQty = variant.lockedQuantity != null;
				int lockedQty = legacyLockedQty
					? Math.min(quantity, Math.max(0, variant.lockedQuantity))
					: (Boolean.TRUE.equals(variant.locked) ? 1 : 0);
				boolean beta = Boolean.TRUE.equals(variant.beta);
				Double condition = variant.condition;
				String source = variant.source;
				String id = variant.id == null || variant.id.isBlank() ? null : variant.id.trim();
				for (int i = 0; i < quantity; i++)
				{
					boolean locked = legacyLockedQty ? i < lockedQty : Boolean.TRUE.equals(variant.locked);
					String rowId = (i == 0) ? id : null;
					rows.add(new OwnedCardInstance(rowId, cardName, isFoil(variant), by, at, locked, condition, beta, source));
				}
			}
		}
		return rows;
	}

	private static List<CardEntry> buildEntries(
		List<OwnedCardInstance> instances,
		boolean includeLocked,
		boolean filterDebugProvenance)
	{
		if (instances == null || instances.isEmpty())
		{
			return List.of();
		}

		List<OwnedCardInstance> sorted = new ArrayList<>();
		for (OwnedCardInstance inst : instances)
		{
			if (inst == null || inst.getCardName() == null || inst.getCardName().trim().isEmpty())
			{
				continue;
			}
			if (filterDebugProvenance && OwnedCardInstance.hasDebugPullMetadata(inst.getPulledByUsername()))
			{
				continue;
			}
			sorted.add(inst);
		}
		if (sorted.isEmpty())
		{
			return List.of();
		}

		sorted.sort(Comparator
			.comparing(OwnedCardInstance::getCardName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(OwnedCardInstance::isFoil)
			.thenComparingLong(OwnedCardInstance::getPulledAtEpochMs)
			.thenComparing(OwnedCardInstance::getPulledByUsername, Comparator.nullsFirst(String::compareToIgnoreCase)));

		Map<String, CardEntry> byName = new LinkedHashMap<>();
		for (OwnedCardInstance inst : sorted)
		{
			String cardName = inst.getCardName().trim();
			CardEntry entry = byName.computeIfAbsent(cardName, n ->
			{
				CardEntry e = new CardEntry();
				e.cardName = n;
				e.variants = new ArrayList<>();
				return e;
			});

			CardVariant variant = new CardVariant();
			variant.id = inst.getInstanceId();
			variant.foil = inst.isFoil() ? Boolean.TRUE : null;
			String by = inst.getPulledByUsername() == null ? "" : inst.getPulledByUsername();
			variant.pulledBy = by.isEmpty() ? null : by;
			long at = inst.getPulledAtEpochMs();
			variant.pulledAt = at <= 0L ? null : at;
			if (includeLocked && inst.isLocked())
			{
				variant.locked = Boolean.TRUE;
			}
			if (inst.getCondition() != null)
			{
				variant.condition = inst.getCondition();
			}
			if (inst.isBeta())
			{
				variant.beta = Boolean.TRUE;
			}
			if (inst.getSource() != null && !inst.getSource().isEmpty())
			{
				variant.source = inst.getSource();
			}
			entry.variants.add(variant);
		}

		for (CardEntry entry : byName.values())
		{
			entry.variants.sort(Comparator
				.comparing(CardEntrySerializer::isFoil)
				.thenComparing(v -> v.pulledAt == null ? 0L : v.pulledAt)
				.thenComparing(v -> v.pulledBy == null ? "" : v.pulledBy, String.CASE_INSENSITIVE_ORDER));
		}

		return new ArrayList<>(byName.values());
	}

	private static boolean isFoil(CardVariant variant)
	{
		return variant != null && Boolean.TRUE.equals(variant.foil);
	}
}

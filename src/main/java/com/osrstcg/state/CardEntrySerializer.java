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

	/** Groups instances into {@link CardEntry} rows for profile/web-share persistence. */
	public static List<CardEntry> buildProfileEntries(List<OwnedCardInstance> instances)
	{
		return buildEntries(instances);
	}

	/**
	 * Reverses {@link #buildProfileEntries}: expands each {@link CardVariant} back into one or more
	 * {@link OwnedCardInstance} rows, honoring the legacy {@code quantity} field by repeating the
	 * variant (only the first repeated row keeps the original instance id). Null/invalid entries and
	 * variants are skipped; zero-or-negative quantities are dropped.
	 */
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
				boolean beta = Boolean.TRUE.equals(variant.beta);
				String id = variant.id == null || variant.id.isBlank() ? null : variant.id.trim();
				for (int i = 0; i < quantity; i++)
				{
					String rowId = (i == 0) ? id : null;
					rows.add(new OwnedCardInstance(rowId, cardName, isFoil(variant), by, at, beta));
				}
			}
		}
		return rows;
	}

	/**
	 * Filters out invalid instances, sorts them by name/foil/pulled-at/pulled-by for stable output,
	 * then groups by card name into {@link CardEntry} rows with variants sorted the same way.
	 */
	private static List<CardEntry> buildEntries(List<OwnedCardInstance> instances)
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
			if (inst.isBeta())
			{
				variant.beta = Boolean.TRUE;
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

	/** Returns whether a variant is marked foil (treats null/absent as non-foil). */
	private static boolean isFoil(CardVariant variant)
	{
		return variant != null && Boolean.TRUE.equals(variant.foil);
	}
}

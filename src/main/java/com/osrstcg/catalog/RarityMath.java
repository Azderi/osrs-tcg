package com.osrstcg.catalog;

import java.awt.Color;
import java.util.Optional;
/**
 * Presentation helpers for rarity tiers (labels/colors). Score and foil values come
 * precomputed from the live catalog / pack pulls - do not recompute curves here.
 */
public final class RarityMath
{
/** Rarity tiers from most to least common, each with a display label and UI color. */
	public enum Tier
	{
		COMMON("Common", new Color(0xFFFFFF)),
		UNCOMMON("Uncommon", new Color(0x2ECC71)),
		RARE("Rare", new Color(0x3498DB)),
		EPIC("Epic", new Color(0x9B59B6)),
		LEGENDARY("Legendary", new Color(0xE74C3C)),
		MYTHIC("Mythic", new Color(0xFF6EC7)),
		GODLY("Godly", new Color(0xF2C94C));

		private final String label;
		private final Color color;
/** Stores the tier's display label and color. */
		Tier(String label, Color color)
		{
			this.label = label;
			this.color = color;
		}
/** The tier's display label (e.g. "Legendary"). */
		public String getLabel()
		{
			return label;
		}
/** The tier's UI color. */
		public Color getColor()
		{
			return color;
		}
	}

	private RarityMath()
	{
	}
/** Parses {@code label} against each {@link Tier#getLabel()}, case-insensitively; empty if null/blank/unmatched. */
	public static Optional<Tier> tryParseTierLabel(String label)
	{
		if (label == null)
		{
			return Optional.empty();
		}
		String trimmed = label.trim();
		if (trimmed.isEmpty())
		{
			return Optional.empty();
		}
		for (Tier tier : Tier.values())
		{
			if (tier.label.equalsIgnoreCase(trimmed))
			{
				return Optional.of(tier);
			}
		}
		return Optional.empty();
	}
/** Like {@link #tryParseTierLabel}, but defaults to {@link Tier#COMMON} instead of empty. */
	public static Tier tierFromLabel(String label)
	{
		return tryParseTierLabel(label).orElse(Tier.COMMON);
	}
}



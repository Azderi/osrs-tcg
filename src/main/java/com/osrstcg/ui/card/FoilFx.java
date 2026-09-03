package com.osrstcg.ui.card;

import com.osrstcg.catalog.RarityMath;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Value;

/**
 * Deterministic foil sparkle layout for a card: a seeded set of {@link Sparkle}s (position, size, timing,
 * color) generated from the pull identity so the same card always renders the same foil pattern.
 */
@Getter
public final class FoilFx
{
	public static final int DEFAULT_SPARKLE_COUNT = 22;

	/** One sparkle's position (percent of card), size, animation timing, and HSL color. */
	@Value
	public static class Sparkle
	{
		double x;
		double y;
		double size;
		double delay;
		double duration;
		double hue;
		double sat;
		double light;
	}

	private final int seed;
	private final List<Sparkle> sparkles;

	/** Stores the seed and wraps the sparkle list as unmodifiable. */
	private FoilFx(int seed, List<Sparkle> sparkles)
	{
		this.seed = seed;
		this.sparkles = Collections.unmodifiableList(sparkles);
	}

	/**
	 * Builds a {@link FoilFx} for a pulled card: seeds a PRNG from the pull identity ({@code cardName}/{@code pulledAt}),
	 * derives a base hue from the card's rarity tier (silver/near-white sparkles for commons, hue-tinted for
	 * everything else), and generates {@code count} randomly placed and timed sparkles.
	 */
	public static FoilFx foilFxFromPulledAt(
		Long pulledAt, int count, String cardName, String tierLabel, Color tierColor)
	{
		int seed = WearFx.wearSeedFromPull(cardName, "", pulledAt, 1);
		WearFx.Mulberry32 rand = new WearFx.Mulberry32(seed);

		RarityMath.Tier tier = resolveTier(tierLabel, tierColor);
		boolean silver = tier == RarityMath.Tier.COMMON;
		double baseHue = foilBaseHue(tier, tierColor);

		int safeCount = Math.max(0, count);
		List<Sparkle> sparkles = new ArrayList<>(safeCount);
		for (int i = 0; i < safeCount; i++)
		{
			double x = 6.0d + rand.next() * 88.0d;
			double y = 8.0d + rand.next() * 84.0d;
			double size = 1.2d + rand.next() * 2.8d;
			double delay = rand.next() * 2.8d;
			double duration = 1.1d + rand.next() * 2.2d;
			double hue;
			double sat;
			double light;
			if (silver)
			{
				hue = rand.next() * 360.0d;
				sat = (2.0d + rand.next() * 10.0d) / 100.0d;
				light = (82.0d + rand.next() * 14.0d) / 100.0d;
			}
			else
			{
				hue = wrapHue(baseHue - 28.0d + rand.next() * 56.0d);
				sat = 0.90d;
				light = 0.72d;
			}
			sparkles.add(new Sparkle(x, y, size, delay, duration, hue, sat, light));
		}

		return new FoilFx(seed, sparkles);
	}

	/**
	 * Resolves the rarity tier to drive sparkle color: prefers a valid {@code tierLabel}; otherwise falls back
	 * to common unless {@code tierColor} carries a distinguishable hue, in which case its hue is used via
	 * {@link #foilBaseHue}.
	 */
	static RarityMath.Tier resolveTier(String tierLabel, Color tierColor)
	{
		RarityMath.Tier fromLabel = RarityMath.tierFromLabel(tierLabel == null ? "" : tierLabel);
		boolean hasLabel = tierLabel != null && !tierLabel.trim().isEmpty();
		if (hasLabel || fromLabel != RarityMath.Tier.COMMON)
		{
			return fromLabel;
		}
		if (hueFromColor(tierColor) == null)
		{
			return RarityMath.Tier.COMMON;
		}
		return fromLabel;
	}

	/** Fixed sparkle hue per known rarity tier; for common or any unlisted tier, derives hue from {@code tierColor} instead (0 if none). */
	static double foilBaseHue(RarityMath.Tier tier, Color tierColor)
	{
		if (tier == null || tier == RarityMath.Tier.COMMON)
		{
			return 0.0d;
		}
		switch (tier)
		{
			case UNCOMMON:
				return 145.0d;
			case RARE:
				return 204.0d;
			case EPIC:
				return 282.0d;
			case LEGENDARY:
				return 6.0d;
			case MYTHIC:
				return 330.0d;
			case GODLY:
				return 45.0d;
			default:
				Double fromColor = hueFromColor(tierColor);
				return fromColor == null ? 0.0d : fromColor;
		}
	}

	/** Extracts the HSV hue (0-360) from an RGB color, or null if the color is too close to gray to have a meaningful hue. */
	static Double hueFromColor(Color color)
	{
		if (color == null)
		{
			return null;
		}
		double r = color.getRed() / 255.0d;
		double g = color.getGreen() / 255.0d;
		double b = color.getBlue() / 255.0d;
		double max = Math.max(r, Math.max(g, b));
		double min = Math.min(r, Math.min(g, b));
		double d = max - min;
		if (d < 0.04d)
		{
			return null;
		}
		double h;
		if (max == r)
		{
			h = ((g - b) / d) % 6.0d;
		}
		else if (max == g)
		{
			h = (b - r) / d + 2.0d;
		}
		else
		{
			h = (r - g) / d + 4.0d;
		}
		h *= 60.0d;
		if (h < 0.0d)
		{
			h += 360.0d;
		}
		return h;
	}

	/** Wraps a hue value into the [0, 360) range. */
	static double wrapHue(double hue)
	{
		double h = hue % 360.0d;
		return h < 0.0d ? h + 360.0d : h;
	}
}

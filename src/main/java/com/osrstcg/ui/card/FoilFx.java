package com.osrstcg.ui.card;

import com.osrstcg.catalog.RarityMath;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic foil tint + sparkle layout. Literal port of
 * {@code osrs-tcg-front/src/album/foilFx.js}: same FNV seed as {@link WearFx} with an empty
 * {@code pulledBy}. Sparkle/tint hues follow rarity; sheen stays cream/cyan in {@link CardFxPainter}.
 */
public final class FoilFx
{
	public static final int DEFAULT_SPARKLE_COUNT = 22;

	/** {@code x}/{@code y} are percent of card size; {@code size} is design px (multiply by card scale). */
	public static final class Sparkle
	{
		private final double x;
		private final double y;
		private final double size;
		private final double delay;
		private final double duration;
		private final double hue;
		/** 0–1 saturation for {@link CardColorMath#hsla}. */
		private final double sat;
		/** 0–1 lightness for {@link CardColorMath#hsla}. */
		private final double light;

		Sparkle(double x, double y, double size, double delay, double duration,
			double hue, double sat, double light)
		{
			this.x = x;
			this.y = y;
			this.size = size;
			this.delay = delay;
			this.duration = duration;
			this.hue = hue;
			this.sat = sat;
			this.light = light;
		}

		public double getX()
		{
			return x;
		}

		public double getY()
		{
			return y;
		}

		public double getSize()
		{
			return size;
		}

		public double getDelay()
		{
			return delay;
		}

		public double getDuration()
		{
			return duration;
		}

		public double getHue()
		{
			return hue;
		}

		public double getSat()
		{
			return sat;
		}

		public double getLight()
		{
			return light;
		}
	}

	private final int seed;
	private final double tintHue;
	/** 0–1; CSS {@code --foil-tint-s}. */
	private final double tintSat;
	/** 0–1; CSS {@code --foil-tint-s-mid}. */
	private final double tintSatMid;
	/** 0–1; CSS {@code --foil-tint-s-end}. */
	private final double tintSatEnd;
	private final double tintAngle;
	private final List<Sparkle> sparkles;

	private FoilFx(
		int seed,
		double tintHue,
		double tintSat,
		double tintSatMid,
		double tintSatEnd,
		double tintAngle,
		List<Sparkle> sparkles)
	{
		this.seed = seed;
		this.tintHue = tintHue;
		this.tintSat = tintSat;
		this.tintSatMid = tintSatMid;
		this.tintSatEnd = tintSatEnd;
		this.tintAngle = tintAngle;
		this.sparkles = Collections.unmodifiableList(sparkles);
	}

	public int getSeed()
	{
		return seed;
	}

	public double getTintHue()
	{
		return tintHue;
	}

	public double getTintSat()
	{
		return tintSat;
	}

	public double getTintSatMid()
	{
		return tintSatMid;
	}

	public double getTintSatEnd()
	{
		return tintSatEnd;
	}

	/** CSS gradient angle in degrees (0 = up, increasing clockwise). */
	public double getTintAngle()
	{
		return tintAngle;
	}

	public List<Sparkle> getSparkles()
	{
		return sparkles;
	}

	public static FoilFx foilFxFromPulledAt(Long pulledAt, String cardName)
	{
		return foilFxFromPulledAt(pulledAt, DEFAULT_SPARKLE_COUNT, cardName, null, null);
	}

	public static FoilFx foilFxFromPulledAt(Long pulledAt, int count, String cardName)
	{
		return foilFxFromPulledAt(pulledAt, count, cardName, null, null);
	}

	/**
	 * @param tierLabel catalog / pull tier label
	 * @param tierColor rarity color; white / near-gray with no label → Common silver path
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
			// Layout RNG order must stay: x, y, size, delay, duration, then color draws.
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

		double tintHue;
		double tintSat;
		double tintSatMid;
		double tintSatEnd;
		if (silver)
		{
			tintHue = 0.0d;
			tintSat = (6.0d + rand.next() * 8.0d) / 100.0d;
			tintSatMid = (8.0d + rand.next() * 10.0d) / 100.0d;
			tintSatEnd = (5.0d + rand.next() * 8.0d) / 100.0d;
		}
		else
		{
			tintHue = wrapHue(baseHue - 16.0d + rand.next() * 32.0d);
			tintSat = 0.85d;
			tintSatMid = 0.90d;
			tintSatEnd = 0.80d;
		}
		double tintAngle = 110.0d + rand.next() * 40.0d;
		return new FoilFx(seed, tintHue, tintSat, tintSatMid, tintSatEnd, tintAngle, sparkles);
	}

	static RarityMath.Tier resolveTier(String tierLabel, Color tierColor)
	{
		RarityMath.Tier fromLabel = RarityMath.tierFromLabel(tierLabel == null ? "" : tierLabel);
		boolean hasLabel = tierLabel != null && !tierLabel.trim().isEmpty();
		if (hasLabel || fromLabel != RarityMath.Tier.COMMON)
		{
			return fromLabel;
		}
		// White / missing chroma → Common (silver foil).
		if (hueFromColor(tierColor) == null)
		{
			return RarityMath.Tier.COMMON;
		}
		return fromLabel;
	}

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

	/** @return hue degrees 0..360, or null for near-gray / invalid */
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

	static double wrapHue(double hue)
	{
		double h = hue % 360.0d;
		return h < 0.0d ? h + 360.0d : h;
	}
}

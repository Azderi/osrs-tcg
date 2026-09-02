package com.osrstcg.ui.card;

import java.awt.Color;

/**
 * Color helpers shared by card rendering/effects: brightening, blending, alpha, clamping, and HSLA conversion.
 */
public final class CardColorMath
{
	private CardColorMath()
	{
	}

	/** Lifts each RGB channel toward white by 35%, preserving alpha; null input returns opaque white. */
	public static Color brighterColor(Color color)
	{
		if (color == null)
		{
			return Color.WHITE;
		}
		return new Color(lift(color.getRed()), lift(color.getGreen()), lift(color.getBlue()), color.getAlpha());
	}

	/** Moves a single 0-255 channel 35% of the way toward 255. */
	private static int lift(int channel)
	{
		return Math.min(255, (int) Math.round(channel + (255 - channel) * 0.35d));
	}

	/**
	 * Linearly interpolates RGB from {@code base} toward {@code tint} by {@code amount} (clamped 0-1),
	 * keeping {@code base}'s alpha. Falls back to whichever color is non-null if the other is null.
	 */
	public static Color blendColors(Color base, Color tint, double amount)
	{
		if (base == null)
		{
			return tint == null ? Color.WHITE : tint;
		}
		if (tint == null)
		{
			return base;
		}
		double t = Math.max(0.0d, Math.min(1.0d, amount));
		return new Color(
			mix(base.getRed(), tint.getRed(), t),
			mix(base.getGreen(), tint.getGreen(), t),
			mix(base.getBlue(), tint.getBlue(), t),
			base.getAlpha());
	}

	/** Linearly interpolates one 0-255 channel from {@code a} to {@code b} by {@code t}, clamped to 0-255. */
	private static int mix(int a, int b, double t)
	{
		return clamp255((int) Math.round(a + (b - a) * t));
	}

	/** Clamps a value to the 0-255 byte range. */
	public static int clamp255(int value)
	{
		return Math.max(0, Math.min(255, value));
	}

	/** Returns {@code c} (white if null) with alpha set from {@code a} (0-1, clamped). */
	public static Color withAlpha(Color c, double a)
	{
		if (c == null)
		{
			c = Color.WHITE;
		}
		int av = clamp255((int) Math.round(Math.max(0.0d, Math.min(1.0d, a)) * 255.0d));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), av);
	}

	/** Converts CSS-style HSLA ({@code hueDeg} any value, {@code saturation}/{@code lightness}/{@code alpha} 0-1) to an RGBA {@link Color}. */
	public static Color hsla(double hueDeg, double saturation, double lightness, double alpha)
	{
		double h = ((hueDeg % 360.0d) + 360.0d) % 360.0d / 360.0d;
		double s = Math.max(0.0d, Math.min(1.0d, saturation));
		double l = Math.max(0.0d, Math.min(1.0d, lightness));

		double r;
		double g;
		double b;
		if (s == 0.0d)
		{
			r = l;
			g = l;
			b = l;
		}
		else
		{
			double q = l < 0.5d ? l * (1.0d + s) : l + s - l * s;
			double p = 2.0d * l - q;
			r = hueToChannel(p, q, h + 1.0d / 3.0d);
			g = hueToChannel(p, q, h);
			b = hueToChannel(p, q, h - 1.0d / 3.0d);
		}

		return new Color(
			clamp255((int) Math.round(r * 255.0d)),
			clamp255((int) Math.round(g * 255.0d)),
			clamp255((int) Math.round(b * 255.0d)),
			clamp255((int) Math.round(Math.max(0.0d, Math.min(1.0d, alpha)) * 255.0d)));
	}

	/** Standard HSL-to-RGB helper: computes one channel from chroma bounds {@code p}/{@code q} at hue offset {@code t}. */
	private static double hueToChannel(double p, double q, double t)
	{
		double tt = t;
		if (tt < 0.0d)
		{
			tt += 1.0d;
		}
		if (tt > 1.0d)
		{
			tt -= 1.0d;
		}
		if (tt < 1.0d / 6.0d)
		{
			return p + (q - p) * 6.0d * tt;
		}
		if (tt < 1.0d / 2.0d)
		{
			return q;
		}
		if (tt < 2.0d / 3.0d)
		{
			return p + (q - p) * (2.0d / 3.0d - tt) * 6.0d;
		}
		return p;
	}
}

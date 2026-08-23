package com.osrstcg.ui.card;

import java.awt.Color;

/**
 * Color helpers matching {@code brighterHex} / {@code blendHex} in
 * {@code osrs-tcg-front/src/album/rarityMath.js}. {@link Color#brighter()} uses a multiplicative
 * curve and does not lift pure-black or saturated channels the same way, so it cannot be used where
 * website parity matters.
 */
public final class CardColorMath
{
	private CardColorMath()
	{
	}

	/** Lifts each channel by {@code (255 - ch) * 0.35}. */
	public static Color brighterColor(Color color)
	{
		if (color == null)
		{
			return Color.WHITE;
		}
		return new Color(lift(color.getRed()), lift(color.getGreen()), lift(color.getBlue()), color.getAlpha());
	}

	private static int lift(int channel)
	{
		return Math.min(255, (int) Math.round(channel + (255 - channel) * 0.35d));
	}

	/** Linear channel mix; {@code amount} is clamped to {@code [0, 1]}. */
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

	private static int mix(int a, int b, double t)
	{
		return clamp255((int) Math.round(a + (b - a) * t));
	}

	public static int clamp255(int value)
	{
		return Math.max(0, Math.min(255, value));
	}

	/**
	 * CSS {@code hsl()} / {@code hsla()}.
	 *
	 * @param hueDeg      hue in degrees (wrapped)
	 * @param saturation  0–1
	 * @param lightness   0–1
	 * @param alpha       0–1
	 */
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

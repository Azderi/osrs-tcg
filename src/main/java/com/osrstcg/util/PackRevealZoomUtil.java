package com.osrstcg.util;

import java.util.function.DoublePredicate;

/**
 * Pack-reveal overlay zoom multipliers: classic-fit {@code 1×}, {@code 1.5×}, and exact {@code 2×}.
 * Layout picks the largest level ≤ preference that fits the canvas.
 */
public final class PackRevealZoomUtil
{
	/** Classic-fixed fitted card size. */
	public static final double NATIVE = 1.0d;
	/** Mid step between {@link #NATIVE} and {@link #DOUBLE}. */
	public static final double ONE_AND_HALF = 1.5d;
	/** Exact pixel-double of {@link #NATIVE}. */
	public static final double DOUBLE = 2.0d;

	/** Ascending discrete zoom levels. */
	public static final double[] LEVELS = {NATIVE, ONE_AND_HALF, DOUBLE};

	public static final double MIN = NATIVE;
	public static final double MAX = DOUBLE;

	private PackRevealZoomUtil()
	{
	}

	/** Snaps to the nearest of {@link #LEVELS}. */
	public static double clamp(double value)
	{
		if (Double.isNaN(value) || Double.isInfinite(value))
		{
			return NATIVE;
		}
		double best = NATIVE;
		double bestDist = Math.abs(value - NATIVE);
		for (int i = 1; i < LEVELS.length; i++)
		{
			double dist = Math.abs(value - LEVELS[i]);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = LEVELS[i];
			}
		}
		return best;
	}

	/**
	 * One wheel notch steps preference through {@link #LEVELS}: scroll up ({@code wheelRotation < 0})
	 * zooms in, scroll down zooms out.
	 */
	public static double nudge(double current, int wheelRotation)
	{
		if (wheelRotation == 0)
		{
			return clamp(current);
		}
		int idx = indexOf(clamp(current));
		if (wheelRotation < 0)
		{
			idx = Math.min(LEVELS.length - 1, idx + 1);
		}
		else
		{
			idx = Math.max(0, idx - 1);
		}
		return LEVELS[idx];
	}

	/**
	 * Largest level ≤ {@code preferred} for which {@code fits} is true (falls back to {@link #NATIVE}).
	 */
	public static double largestFittingAtMost(double preferred, DoublePredicate fits)
	{
		double pref = clamp(preferred);
		double best = NATIVE;
		for (double level : LEVELS)
		{
			if (level > pref + 1e-9d)
			{
				break;
			}
			if (fits != null && fits.test(level))
			{
				best = level;
			}
		}
		return best;
	}

	/** Scales a native (1×) pixel length by a discrete zoom mul. */
	public static int scalePx(int nativePx, double mul)
	{
		double level = clamp(mul);
		if (Double.compare(level, DOUBLE) == 0)
		{
			return Math.max(1, nativePx * 2);
		}
		if (Double.compare(level, ONE_AND_HALF) == 0)
		{
			return Math.max(1, (int) Math.round(nativePx * ONE_AND_HALF));
		}
		return Math.max(1, nativePx);
	}

	private static int indexOf(double level)
	{
		for (int i = 0; i < LEVELS.length; i++)
		{
			if (Double.compare(LEVELS[i], level) == 0)
			{
				return i;
			}
		}
		return 0;
	}
}

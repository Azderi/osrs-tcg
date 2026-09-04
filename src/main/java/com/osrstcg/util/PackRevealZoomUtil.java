package com.osrstcg.util;

import java.util.function.DoublePredicate;
/** Discrete zoom-level math for the pack reveal view: snapping, stepping, and pixel scaling across the fixed {@link #LEVELS} set. */
public final class PackRevealZoomUtil
{
	public static final double NATIVE = 1.0d;
	public static final double ONE_AND_HALF = 1.5d;
	public static final double DOUBLE = 2.0d;
/** Ascending supported zoom levels. */
	public static final double[] LEVELS = {NATIVE, ONE_AND_HALF, DOUBLE};
/** No instances. */
	private PackRevealZoomUtil()
	{
	}
/** @return the nearest value in {@link #LEVELS} to {@code value}; {@link #NATIVE} for NaN/infinite input. */
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
	 * Steps {@code current} (snapped first) one entry along {@link #LEVELS} per mouse-wheel notch:
	 * negative rotation (wheel up) zooms in, positive zooms out. No-op if {@code wheelRotation} is 0.
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
	 * Largest level that is at most {@code preferred} (snapped) and satisfies {@code fits}, e.g. a
	 * viewport-size check. @return {@link #NATIVE} if no level (other than native) satisfies {@code fits}.
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
/** @return {@code nativePx} scaled by the zoom level nearest {@code mul}, rounded and floored at 1. */
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
/** @return index of {@code level} in {@link #LEVELS}, or 0 if not present (should always be an exact level). */
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

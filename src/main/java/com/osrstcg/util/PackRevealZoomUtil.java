package com.osrstcg.util;

import java.util.function.DoublePredicate;

public final class PackRevealZoomUtil
{
	public static final double NATIVE = 1.0d;
	public static final double ONE_AND_HALF = 1.5d;
	public static final double DOUBLE = 2.0d;

	public static final double[] LEVELS = {NATIVE, ONE_AND_HALF, DOUBLE};

	private PackRevealZoomUtil()
	{
	}

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

package com.osrstcg.util;

public final class PackRevealZoomUtil
{
	public static final double MIN = 0.35d;
	public static final double MAX = 2.5d;

	private PackRevealZoomUtil()
	{
	}

	public static double clamp(double value)
	{
		if (Double.isNaN(value) || Double.isInfinite(value))
		{
			return 1.0d;
		}
		return Math.max(MIN, Math.min(MAX, value));
	}
}

package com.osrstcg.pack;

/**
 * CSS {@code cubic-bezier(0.2, 0.7, 0.2, 1)} timing - maps linear 0..1 time to eased flip progress.
 */
final class CardFlipEasing
{
	private CardFlipEasing()
	{
	}

	/**
	 * Eases a linear 0..1 flip time into 0..1 progress along the cubic-bezier curve, via Newton-Raphson
	 * inversion of the bezier's x(u) against {@code t}.
	 */
	static float flipEase(float t)
	{
		if (t <= 0f)
		{
			return 0f;
		}
		if (t >= 1f)
		{
			return 1f;
		}
		// Unit cubic Bezier with P0=(0,0), P1=(0.2,0.7), P2=(0.2,1), P3=(1,1).
		float u = t;
		for (int i = 0; i < 6; i++)
		{
			float x = cubicBezier(u, 0.2f, 0.2f);
			float dx = cubicBezierDerivative(u, 0.2f, 0.2f);
			if (Math.abs(dx) < 1e-6f)
			{
				break;
			}
			u -= (x - t) / dx;
			if (u < 0f)
			{
				u = 0f;
			}
			else if (u > 1f)
			{
				u = 1f;
			}
		}
		return cubicBezier(u, 0.7f, 1f);
	}

	/** Evaluates a single component of the unit cubic bezier at parameter {@code u} for control points {@code p1}/{@code p2}. */
	private static float cubicBezier(float u, float p1, float p2)
	{
		float omu = 1f - u;
		return 3f * omu * omu * u * p1 + 3f * omu * u * u * p2 + u * u * u;
	}

	/** Derivative of {@link #cubicBezier} with respect to {@code u}, used by the Newton-Raphson step. */
	private static float cubicBezierDerivative(float u, float p1, float p2)
	{
		float omu = 1f - u;
		return 3f * omu * omu * p1 + 6f * omu * u * (p2 - p1) + 3f * u * u * (1f - p2);
	}
}

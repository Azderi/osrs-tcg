package com.osrstcg.ui.card;

public final class FoilSparkleAnimation
{
	private static final double EASE_X1 = 0.42d;
	private static final double EASE_Y1 = 0.0d;
	private static final double EASE_X2 = 0.58d;
	private static final double EASE_Y2 = 1.0d;

	public static final class Sample
	{
		private final double opacity;
		private final double scale;

		Sample(double opacity, double scale)
		{
			this.opacity = opacity;
			this.scale = scale;
		}

		public double getOpacity()
		{
			return opacity;
		}

		public double getScale()
		{
			return scale;
		}
	}

	private FoilSparkleAnimation()
	{
	}

	public static Sample sample(double delaySec, double durationSec, double timeSec)
	{
		if (durationSec <= 0.0d)
		{
			return new Sample(0.0d, 0.4d);
		}
		double elapsed = timeSec - delaySec;
		if (elapsed < 0.0d)
		{
			return new Sample(0.0d, 0.4d);
		}
		double phase = (elapsed / durationSec) % 1.0d;
		if (phase < 0.0d)
		{
			phase += 1.0d;
		}
		return samplePhase(phase);
	}

	static Sample samplePhase(double phase)
	{
		double p = phase;
		if (p <= 0.0d || p >= 1.0d)
		{
			return new Sample(0.0d, 0.4d);
		}
		if (p <= 0.40d)
		{
			double u = easeInOut(p / 0.40d);
			return lerp(0.0d, 0.4d, 0.95d, 1.0d, u);
		}
		if (p <= 0.55d)
		{
			return new Sample(0.95d, 1.0d);
		}
		if (p <= 0.70d)
		{
			double u = easeInOut((p - 0.55d) / 0.15d);
			return lerp(0.95d, 1.0d, 0.2d, 0.7d, u);
		}
		double u = easeInOut((p - 0.70d) / 0.30d);
		return lerp(0.2d, 0.7d, 0.0d, 0.4d, u);
	}

	private static Sample lerp(double o0, double s0, double o1, double s1, double u)
	{
		return new Sample(o0 + (o1 - o0) * u, s0 + (s1 - s0) * u);
	}

	static double easeInOut(double t)
	{
		double x = Math.max(0.0d, Math.min(1.0d, t));
		if (x <= 0.0d)
		{
			return 0.0d;
		}
		if (x >= 1.0d)
		{
			return 1.0d;
		}
		return cubicBezierY(EASE_X1, EASE_Y1, EASE_X2, EASE_Y2, x);
	}

	private static double cubicBezierY(double x1, double y1, double x2, double y2, double x)
	{
		double t = x;
		for (int i = 0; i < 8; i++)
		{
			double xEst = bezierCoord(t, x1, x2);
			double dx = bezierDerivative(t, x1, x2);
			if (Math.abs(dx) < 1e-6d)
			{
				break;
			}
			t -= (xEst - x) / dx;
			t = Math.max(0.0d, Math.min(1.0d, t));
		}
		return bezierCoord(t, y1, y2);
	}

	private static double bezierCoord(double t, double p1, double p2)
	{
		double u = 1.0d - t;
		return 3.0d * u * u * t * p1 + 3.0d * u * t * t * p2 + t * t * t;
	}

	private static double bezierDerivative(double t, double p1, double p2)
	{
		double u = 1.0d - t;
		return 3.0d * u * u * p1 + 6.0d * u * t * (p2 - p1) + 3.0d * t * t * (1.0d - p2);
	}
}

package com.osrstcg.ui.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic wear layers for the card inspect view. Literal port of
 * {@code osrs-tcg-front/src/album/wearFx.js} - the FNV-1a seed and the mulberry32 draw order are
 * byte-identical to the website, so the same {@code (name, pulledBy, pulledAt, condition)} yields
 * the same scratches and stains in the plugin and in the browser.
 *
 * <p>Any change here must preserve the exact number and order of {@link Mulberry32#next()} calls.</p>
 */
public final class WearFx
{
	public enum SpotShape
	{
		ROUND,
		ELLIPSE,
		SMEAR,
		BLOB,
		SPLOTCH
	}

	/**
	 * mulberry32 with JS {@code Math.imul} / {@code >>>} semantics expressed in 32-bit Java ints.
	 */
	public static final class Mulberry32
	{
		private int a;

		public Mulberry32(int seed)
		{
			this.a = seed == 0 ? 0x9E3779B9 : seed;
		}

		/** Next value in {@code [0, 1)}. */
		public double next()
		{
			a = a + 0x6D2B79F5;
			int t = (a ^ (a >>> 15)) * (1 | a);
			t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
			return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0d;
		}
	}

	/** A single hairline abrasion. {@code x}/{@code y} are percent of card size, {@code len} percent of width. */
	public static final class Scratch
	{
		private final double x;
		private final double y;
		private final double len;
		private final double angle;
		private final double opacity;

		Scratch(double x, double y, double len, double angle, double opacity)
		{
			this.x = x;
			this.y = y;
			this.len = len;
			this.angle = angle;
			this.opacity = opacity;
		}

		public double getX()
		{
			return x;
		}

		public double getY()
		{
			return y;
		}

		public double getLen()
		{
			return len;
		}

		public double getAngle()
		{
			return angle;
		}

		public double getOpacity()
		{
			return opacity;
		}
	}

	/**
	 * A dirt stain. {@code x}/{@code y} are percent of card size (centered), {@code w}/{@code h} percent
	 * of card width/height, {@code borderRadius} the eight CSS corner percentages
	 * (horizontal TL, TR, BR, BL then vertical TL, TR, BR, BL).
	 */
	public static final class Spot
	{
		private final double x;
		private final double y;
		private final double w;
		private final double h;
		private final double rotate;
		private final double[] borderRadius;
		private final double blur;
		private final SpotShape shape;
		private final double opacity;

		Spot(double x, double y, double w, double h, double rotate, double[] borderRadius, double blur, SpotShape shape, double opacity)
		{
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.rotate = rotate;
			this.borderRadius = borderRadius;
			this.blur = blur;
			this.shape = shape;
			this.opacity = opacity;
		}

		public double getX()
		{
			return x;
		}

		public double getY()
		{
			return y;
		}

		public double getW()
		{
			return w;
		}

		public double getH()
		{
			return h;
		}

		public double getRotate()
		{
			return rotate;
		}

		public double[] getBorderRadius()
		{
			return borderRadius.clone();
		}

		public double getBlur()
		{
			return blur;
		}

		public SpotShape getShape()
		{
			return shape;
		}

		public double getOpacity()
		{
			return opacity;
		}
	}

	private final int seed;
	private final CardGrade grade;
	private final double intensity;
	private final double fade;
	private final double scratchMix;
	private final double dirtMix;
	private final double edgeMix;
	private final boolean showEdges;
	private final boolean showScratches;
	private final List<Scratch> scratches;
	private final List<Spot> spots;

	private WearFx(int seed, CardGrade grade, double intensity, double fade, double scratchMix, double dirtMix, double edgeMix,
		boolean showEdges, boolean showScratches, List<Scratch> scratches, List<Spot> spots)
	{
		this.seed = seed;
		this.grade = grade;
		this.intensity = intensity;
		this.fade = fade;
		this.scratchMix = scratchMix;
		this.dirtMix = dirtMix;
		this.edgeMix = edgeMix;
		this.showEdges = showEdges;
		this.showScratches = showScratches;
		this.scratches = Collections.unmodifiableList(scratches);
		this.spots = Collections.unmodifiableList(spots);
	}

	public int getSeed()
	{
		return seed;
	}

	public CardGrade getGrade()
	{
		return grade;
	}

	public double getIntensity()
	{
		return intensity;
	}

	public double getFade()
	{
		return fade;
	}

	public double getScratchMix()
	{
		return scratchMix;
	}

	public double getDirtMix()
	{
		return dirtMix;
	}

	public double getEdgeMix()
	{
		return edgeMix;
	}

	public boolean isShowEdges()
	{
		return showEdges;
	}

	public boolean isShowScratches()
	{
		return showScratches;
	}

	public List<Scratch> getScratches()
	{
		return scratches;
	}

	public List<Spot> getSpots()
	{
		return spots;
	}

	/** FNV-1a 32-bit, matching {@code hashStringToSeed} (UTF-16 code units). */
	public static int hashStringToSeed(String str)
	{
		int h = 0x811C9DC5;
		if (str == null)
		{
			return h;
		}
		for (int i = 0; i < str.length(); i++)
		{
			h ^= str.charAt(i);
			h *= 16777619;
		}
		return h;
	}

	/**
	 * {@code hash("name|pulledBy|pulledAt")}, or {@code fallback} when all three are empty/zero.
	 * The seed string must stay byte-identical to the site's.
	 */
	public static int wearSeedFromPull(String cardName, String pulledBy, Long pulledAt, int fallback)
	{
		String name = cardName == null ? "" : cardName.trim();
		String by = pulledBy == null ? "" : pulledBy.trim();
		long at = pulledAt == null ? 0L : pulledAt;
		if (name.isEmpty() && by.isEmpty() && at == 0L)
		{
			return fallback == 0 ? 1 : fallback;
		}
		int h = hashStringToSeed(name + "|" + by + "|" + at);
		return h == 0 ? 1 : h;
	}

	/**
	 * Wear for one instance, or {@code null} when the grade is unknown (no condition) or S (beta / mint).
	 *
	 * @param condition 0.01–100, or {@code null} when absent (beta / migrated instances)
	 */
	public static WearFx wearFxFromCondition(Double condition, Long pulledAt, boolean beta, String cardName, String pulledBy)
	{
		CardGrade grade = CardGrade.gradeFromVariant(beta, condition);
		if (grade == null)
		{
			return null;
		}
		double intensity = grade.getIntensity();
		double fade = grade.getFade();
		if (intensity <= 0.0d && fade <= 0.0d)
		{
			return null;
		}

		int fallback = conditionFallbackSeed(condition);
		int seed = wearSeedFromPull(cardName, pulledBy, pulledAt, fallback);
		Mulberry32 rand = new Mulberry32(seed);

		// Wear profile mix: 0 = dirt-heavy, 1 = scratch-heavy. Inversely shared budget.
		double profile = rand.next();
		boolean showScratches = grade != CardGrade.A && grade != CardGrade.S;
		boolean showEdges = showScratches;

		double scratchMix;
		double dirtMix;
		double edgeMix;
		if (showScratches)
		{
			scratchMix = 0.22d + profile * 0.78d;
			dirtMix = 0.22d + (1.0d - profile) * 0.78d;
			edgeMix = 0.25d + rand.next() * 0.55d;
			edgeMix *= 0.55d + scratchMix * 0.45d;
		}
		else
		{
			dirtMix = 0.55d + rand.next() * 0.45d;
			scratchMix = 0.0d;
			edgeMix = 0.0d;
		}

		List<Scratch> scratches = new ArrayList<>();
		if (showScratches)
		{
			long scratchCount = Math.round((1.0d + intensity * 14.0d) * scratchMix);
			for (long i = 0; i < scratchCount; i++)
			{
				double x = 6.0d + rand.next() * 88.0d;
				double y = 8.0d + rand.next() * 84.0d;
				double len = 8.0d + rand.next() * (10.0d + intensity * 30.0d * scratchMix);
				double angle = -42.0d + rand.next() * 84.0d;
				double opacity = (0.12d + rand.next() * (0.14d + intensity * 0.38d)) * (0.65d + scratchMix * 0.35d);
				scratches.add(new Scratch(x, y, len, angle, opacity));
			}
		}

		boolean gradeA = grade == CardGrade.A;
		long spotBudget = gradeA
			? Math.round((2.0d + rand.next() * 3.0d) * dirtMix)
			: Math.round((1.0d + intensity * 12.0d) * dirtMix);

		List<Spot> spots = new ArrayList<>();
		for (long i = 0; i < spotBudget; i++)
		{
			double baseSize = (gradeA ? 3.0d : 4.0d)
				+ rand.next() * (gradeA ? 5.0d : 6.0d + intensity * 14.0d * dirtMix);
			SpotGeometry geom = spotGeometry(rand, baseSize);
			double x = 8.0d + rand.next() * 84.0d;
			double y = 10.0d + rand.next() * 80.0d;
			double opacity = ((gradeA ? 0.06d : 0.09d)
				+ rand.next() * (gradeA ? 0.08d : 0.1d + intensity * 0.28d))
				* (0.6d + dirtMix * 0.4d);
			spots.add(new Spot(x, y, geom.w, geom.h, geom.rotate, geom.borderRadius, geom.blur, geom.shape, opacity));
		}

		return new WearFx(seed, grade, intensity, fade, scratchMix, dirtMix, edgeMix, showEdges, showScratches, scratches, spots);
	}

	/** {@code Math.round(Number(condition) * 100) || 1}. */
	private static int conditionFallbackSeed(Double condition)
	{
		if (condition == null || condition.isNaN() || condition.isInfinite())
		{
			return 1;
		}
		long v = Math.round(condition * 100.0d);
		if (v == 0L)
		{
			return 1;
		}
		return (int) v;
	}

	private static final class SpotGeometry
	{
		private final SpotShape shape;
		private final double w;
		private final double h;
		private final double rotate;
		private final double[] borderRadius;
		private final double blur;

		private SpotGeometry(SpotShape shape, double w, double h, double rotate, double[] borderRadius, double blur)
		{
			this.shape = shape;
			this.w = w;
			this.h = h;
			this.rotate = rotate;
			this.borderRadius = borderRadius;
			this.blur = blur;
		}
	}

	/** Eight CSS corner percentages, drawn in template order so the RNG stream matches the site. */
	private static double[] organicBorderRadius(Mulberry32 rand)
	{
		double[] corners = new double[8];
		for (int i = 0; i < 8; i++)
		{
			corners[i] = Math.round(22.0d + rand.next() * 58.0d);
		}
		return corners;
	}

	private static double[] uniformRadius(double percent)
	{
		double[] corners = new double[8];
		for (int i = 0; i < 8; i++)
		{
			corners[i] = percent;
		}
		return corners;
	}

	private static SpotGeometry spotGeometry(Mulberry32 rand, double baseSize)
	{
		double pick = rand.next();
		SpotShape shape;
		if (pick < 0.2d)
		{
			shape = SpotShape.ROUND;
		}
		else if (pick < 0.42d)
		{
			shape = SpotShape.ELLIPSE;
		}
		else if (pick < 0.62d)
		{
			shape = SpotShape.SMEAR;
		}
		else if (pick < 0.82d)
		{
			shape = SpotShape.BLOB;
		}
		else
		{
			shape = SpotShape.SPLOTCH;
		}

		switch (shape)
		{
			case ELLIPSE:
			{
				double w = baseSize * (0.65d + rand.next() * 0.95d);
				double h = baseSize * (0.38d + rand.next() * 0.58d);
				double rotate = rand.next() * 180.0d;
				return new SpotGeometry(shape, w, h, rotate, uniformRadius(50.0d), 0.0d);
			}
			case SMEAR:
			{
				double w = baseSize * (1.4d + rand.next() * 2.4d);
				double h = baseSize * (0.16d + rand.next() * 0.3d);
				double rotate = rand.next() * 180.0d;
				double radius = Math.round(30.0d + rand.next() * 25.0d);
				double blur = 0.35d + rand.next() * 0.65d;
				return new SpotGeometry(shape, w, h, rotate, uniformRadius(radius), blur);
			}
			case BLOB:
			{
				double w = baseSize * (0.72d + rand.next() * 0.75d);
				double h = baseSize * (0.62d + rand.next() * 0.85d);
				double rotate = rand.next() * 360.0d;
				double[] radius = organicBorderRadius(rand);
				double blur = 0.2d + rand.next() * 0.55d;
				return new SpotGeometry(shape, w, h, rotate, radius, blur);
			}
			case SPLOTCH:
			{
				double w = baseSize * (0.82d + rand.next() * 0.62d);
				double h = baseSize * (0.7d + rand.next() * 0.72d);
				double rotate = -45.0d + rand.next() * 90.0d;
				double[] radius = organicBorderRadius(rand);
				double blur = 0.25d + rand.next() * 0.75d;
				return new SpotGeometry(shape, w, h, rotate, radius, blur);
			}
			default:
				return new SpotGeometry(SpotShape.ROUND, baseSize, baseSize, 0.0d, uniformRadius(50.0d), 0.0d);
		}
	}
}

package com.osrstcg.ui.card;

/**
 * Card condition grade (S best to E worst), each carrying a wear-effect intensity and color-fade amount
 * used to drive {@link WearFx} and {@link CardFxPainter}.
 */
public enum CardGrade
{
	S(0.0d, 0.0d),
	A(0.14d, 0.12d),
	B(0.28d, 0.22d),
	C(0.48d, 0.36d),
	D(0.68d, 0.50d),
	E(0.85d, 0.64d);

	private final double intensity;
	private final double fade;

	/** Stores the wear intensity and color-fade amounts for this grade. */
	CardGrade(double intensity, double fade)
	{
		this.intensity = intensity;
		this.fade = fade;
	}

	/** Strength of scratch/dirt/edge wear effects for this grade (0 = none). */
	public double getIntensity()
	{
		return intensity;
	}

	/** Amount of color desaturation/darkening applied for this grade (0 = none). */
	public double getFade()
	{
		return fade;
	}

	/** Maps a 0-100 condition value to a grade band; returns null for a null/NaN/infinite condition. */
	public static CardGrade gradeFromCondition(Double condition)
	{
		if (condition == null || condition.isNaN() || condition.isInfinite())
		{
			return null;
		}
		double c = condition;
		if (c >= 95.0d)
		{
			return S;
		}
		if (c >= 75.0d)
		{
			return A;
		}
		if (c >= 50.0d)
		{
			return B;
		}
		if (c >= 25.0d)
		{
			return C;
		}
		if (c >= 5.0d)
		{
			return D;
		}
		return E;
	}

	/** Beta-migrated copies are always graded S regardless of condition; otherwise defers to {@link #gradeFromCondition}. */
	public static CardGrade gradeFromVariant(boolean beta, Double condition)
	{
		if (beta)
		{
			return S;
		}
		return gradeFromCondition(condition);
	}

	/** Formats a condition value to two decimal places, or null for a null/NaN/infinite input. */
	public static String formatCondition(Double condition)
	{
		if (condition == null || condition.isNaN() || condition.isInfinite())
		{
			return null;
		}
		return String.format(java.util.Locale.US, "%.2f", condition);
	}
}

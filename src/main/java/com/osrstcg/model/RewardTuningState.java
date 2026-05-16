package com.osrstcg.model;

/**
 * Foil rate and credit multipliers stored per profile. Editable from the sidebar only while the collection is empty.
 */
public final class RewardTuningState
{
	public static final RewardTuningState DEFAULTS = new RewardTuningState(1, 1.0d, 1.0d, 1.0d);

	private final int foilChancePercent;
	private final double killCreditMultiplier;
	private final double levelUpCreditMultiplier;
	private final double xpCreditMultiplier;

	public RewardTuningState(int foilChancePercent, double killCreditMultiplier,
		double levelUpCreditMultiplier, double xpCreditMultiplier)
	{
		this.foilChancePercent = clampFoil(foilChancePercent);
		this.killCreditMultiplier = clampMultiplier(killCreditMultiplier);
		this.levelUpCreditMultiplier = clampMultiplier(levelUpCreditMultiplier);
		this.xpCreditMultiplier = clampMultiplier(xpCreditMultiplier);
	}

	public static RewardTuningState mergeSerialized(Integer foil, Double kill, Double level, Double xp)
	{
		RewardTuningState d = DEFAULTS;
		return new RewardTuningState(
			foil != null ? foil : d.foilChancePercent,
			kill != null ? kill : d.killCreditMultiplier,
			level != null ? level : d.levelUpCreditMultiplier,
			xp != null ? xp : d.xpCreditMultiplier
		);
	}

	public int getFoilChancePercent()
	{
		return foilChancePercent;
	}

	public double getKillCreditMultiplier()
	{
		return killCreditMultiplier;
	}

	public double getLevelUpCreditMultiplier()
	{
		return levelUpCreditMultiplier;
	}

	public double getXpCreditMultiplier()
	{
		return xpCreditMultiplier;
	}

	private static int clampFoil(int value)
	{
		return Math.max(0, Math.min(100, value));
	}

	private static double clampMultiplier(double value)
	{
		if (Double.isNaN(value) || Double.isInfinite(value))
		{
			return 1.0d;
		}
		return Math.max(0.0d, Math.min(100.0d, value));
	}

	/**
	 * True if another profile's locked tuning matches this one (party card transfers require identical settings).
	 */
	public boolean matchesPartnerTuning(RewardTuningState other)
	{
		if (other == null)
		{
			return false;
		}
		if (foilChancePercent != other.foilChancePercent)
		{
			return false;
		}
		return multEqual(killCreditMultiplier, other.killCreditMultiplier)
			&& multEqual(levelUpCreditMultiplier, other.levelUpCreditMultiplier)
			&& multEqual(xpCreditMultiplier, other.xpCreditMultiplier);
	}

	private static boolean multEqual(double a, double b)
	{
		if (Double.isNaN(a) || Double.isNaN(b))
		{
			return false;
		}
		return Double.compare(a, b) == 0 || Math.abs(a - b) < 1e-9d;
	}
}

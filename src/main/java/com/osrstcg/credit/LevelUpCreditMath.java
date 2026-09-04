package com.osrstcg.credit;

import net.runelite.api.Experience;
/** Level-up credit curve used by {@link CreditAwardService}. */
final class LevelUpCreditMath
{
/** Credits awarded for reaching level 1-2 (curve floor). */
	static final int LEVEL_UP_REWARD_FLOOR = 1_250;
/** Credits awarded for reaching {@link net.runelite.api.Experience#MAX_REAL_LEVEL} (curve cap). */
	static final int LEVEL_UP_REWARD_CAP = 25_000;
/** Number of levels the curve ramps over, from level 2 to {@link net.runelite.api.Experience#MAX_REAL_LEVEL}. */
	static final int LEVEL_UP_PROGRESS_LEVELS = 97;
/** Exponent shaping the reward curve; higher values back-load rewards toward higher levels. */
	static final double LEVEL_UP_CURVE_STEEPNESS = 2.5d;

	private LevelUpCreditMath()
	{
	}
/** Credit reward (credits) for reaching {@code level}, clamped and interpolated along the reward curve. */
	static int levelUpReward(int level)
	{
		int clamped = clampLevel(level);
		if (clamped <= 2)
		{
			return LEVEL_UP_REWARD_FLOOR;
		}
		if (clamped >= Experience.MAX_REAL_LEVEL)
		{
			return LEVEL_UP_REWARD_CAP;
		}

		double progress = (clamped - 2.0d) / LEVEL_UP_PROGRESS_LEVELS;
		double curve = Math.pow(progress, LEVEL_UP_CURVE_STEEPNESS);
		double multiplier = Math.pow((double) LEVEL_UP_REWARD_CAP / LEVEL_UP_REWARD_FLOOR, curve);
		return (int) Math.round(LEVEL_UP_REWARD_FLOOR * multiplier);
	}
/** Level for a given xp amount, clamped to the valid level range. */
	static int levelForXp(int xp)
	{
		return clampLevel(Experience.getLevelForXp(Math.max(0, xp)));
	}
/** Clamps a level to {@code [1, Experience.MAX_VIRT_LEVEL]}. */
	static int clampLevel(int level)
	{
		if (level < 1)
		{
			return 1;
		}
		return Math.min(level, Experience.MAX_VIRT_LEVEL);
	}
}

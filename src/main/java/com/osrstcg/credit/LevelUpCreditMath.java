package com.osrstcg.credit;

import net.runelite.api.Experience;

/** Level-up credit curve used by {@link CreditAwardService}. */
final class LevelUpCreditMath
{
	static final int LEVEL_UP_REWARD_FLOOR = 1_250;
	static final int LEVEL_UP_REWARD_CAP = 25_000;
	static final int LEVEL_UP_PROGRESS_LEVELS = 97;
	static final double LEVEL_UP_CURVE_STEEPNESS = 2.5d;

	private LevelUpCreditMath()
	{
	}

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

	static int levelForXp(int xp)
	{
		return clampLevel(Experience.getLevelForXp(Math.max(0, xp)));
	}

	static int clampLevel(int level)
	{
		if (level < 1)
		{
			return 1;
		}
		return Math.min(level, Experience.MAX_VIRT_LEVEL);
	}
}

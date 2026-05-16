package com.runelitetcg.service;

import com.runelitetcg.data.CardDefinition;

/** Credits awarded when selling duplicate or individually selected collection copies. */
public final class DuplicateSellCredits
{
	public static final long SCORE_DIVISOR = 100L;
	public static final long MIN_CREDITS = 10L;

	private DuplicateSellCredits()
	{
	}

	public static long creditsForRoundedScore(long score)
	{
		return Math.max(MIN_CREDITS, score / SCORE_DIVISOR);
	}

	public static long creditsForCard(CardDefinition card)
	{
		return creditsForCard(card, false);
	}

	public static long creditsForCard(CardDefinition card, boolean foil)
	{
		if (card == null)
		{
			return MIN_CREDITS;
		}
		long rounded = foil
			? RarityMath.foilAdjustedScoreRounded(card)
			: Math.round(RarityMath.score(card));
		return creditsForRoundedScore(rounded);
	}
}

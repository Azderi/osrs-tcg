package com.osrstcg.credit;

import com.osrstcg.catalog.CardDefinition;

/** Credits awarded when selling duplicate or individually selected collection copies. */
public final class DuplicateSellCredits
{
	public static final long SCORE_DIVISOR = 200L;
	public static final long MIN_CREDITS = 10L;

	private DuplicateSellCredits()
	{
	}

	static long creditsForRoundedScore(long score)
	{
		return Math.max(MIN_CREDITS, score / SCORE_DIVISOR);
	}

	public static long creditsForCard(CardDefinition card, boolean foil)
	{
		if (card == null)
		{
			return MIN_CREDITS;
		}
		long rounded = card.displayScore(foil);
		return creditsForRoundedScore(rounded);
	}
}

package com.osrstcg.credit;

/** XP → credit conversion used by {@link CreditAwardService}. */
final class XpCreditMath
{
	/** XP per credit-eligible chunk for regular skills. */
	static final long XP_PER_CREDIT_CHUNK = 1000L;
	/** Credits awarded per {@link #XP_PER_CREDIT_CHUNK} XP chunk for regular skills. */
	static final long CREDITS_PER_CHUNK = 100L;
	/** XP per credit-eligible chunk for Slayer XP. */
	static final long SLAYER_XP_PER_CHUNK = 100L;
	/** Credits awarded per {@link #SLAYER_XP_PER_CHUNK} XP chunk for Slayer XP. */
	static final long SLAYER_CREDITS_PER_CHUNK = 10L;

	private XpCreditMath()
	{
	}

	/** Converts a count of regular-skill XP chunks into credits. */
	static long creditsFromXpChunks(long chunks)
	{
		return chunks * CREDITS_PER_CHUNK;
	}
}

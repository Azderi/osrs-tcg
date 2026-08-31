package com.osrstcg.credit;

/** XP → credit conversion used by {@link CreditAwardService}. */
final class XpCreditMath
{
	static final long XP_PER_CREDIT_CHUNK = 1000L;
	static final long CREDITS_PER_CHUNK = 100L;
	static final long SLAYER_XP_PER_CHUNK = 100L;
	static final long SLAYER_CREDITS_PER_CHUNK = 10L;

	private XpCreditMath()
	{
	}

	static long creditsFromXpChunks(long chunks)
	{
		return chunks * CREDITS_PER_CHUNK;
	}
}

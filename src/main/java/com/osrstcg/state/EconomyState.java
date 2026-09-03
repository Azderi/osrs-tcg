package com.osrstcg.state;

/** Immutable snapshot of a player's credit balance and lifetime pack-open count. */
public final class EconomyState
{
	private final long credits;
	private final long openedPacks;

	/** Clamps both values to non-negative. */
	public EconomyState(long credits, long openedPacks)
	{
		this.credits = Math.max(0L, credits);
		this.openedPacks = Math.max(0L, openedPacks);
	}

	/** Returns a zero-credits, zero-packs state. */
	public static EconomyState empty()
	{
		return new EconomyState(0L, 0L);
	}

	/** Returns the current credit balance. */
	public long getCredits()
	{
		return credits;
	}

	/** Returns the total number of packs opened. */
	public long getOpenedPacks()
	{
		return openedPacks;
	}
}

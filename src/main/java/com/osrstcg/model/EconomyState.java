package com.osrstcg.model;

public final class EconomyState
{
	private final long credits;
	private final long openedPacks;

	public EconomyState(long credits, long openedPacks)
	{
		this.credits = Math.max(0L, credits);
		this.openedPacks = Math.max(0L, openedPacks);
	}

	public static EconomyState empty()
	{
		return new EconomyState(0L, 0L);
	}

	public long getCredits()
	{
		return credits;
	}

	public long getOpenedPacks()
	{
		return openedPacks;
	}
}

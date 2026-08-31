package com.osrstcg.state;

/**
 * Unacked optimistic credit gains (session UI only). Mutated only from {@link TcgStateService}
 * synchronized methods.
 */
final class OptimisticCreditBuffer
{
	private long pending;

	long get()
	{
		return pending;
	}

	void clear()
	{
		pending = 0L;
	}

	void add(long amount)
	{
		if (amount > 0L)
		{
			pending += amount;
		}
	}

	void clearAmount(long amount)
	{
		if (amount <= 0L || pending <= 0L)
		{
			return;
		}
		pending = Math.max(0L, pending - amount);
	}
}

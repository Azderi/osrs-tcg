package com.osrstcg.state;

/**
 * Unacked optimistic credit gains (session UI only). Mutated only from {@link TcgStateService}
 * synchronized methods.
 */
final class OptimisticCreditBuffer
{
	private long pending;

	/** Returns the currently pending (unacked) optimistic credit amount. */
	long get()
	{
		return pending;
	}

	/** Resets the pending amount to zero. */
	void clear()
	{
		pending = 0L;
	}

	/** Adds an optimistic credit gain; non-positive amounts are ignored. */
	void add(long amount)
	{
		if (amount > 0L)
		{
			pending += amount;
		}
	}

	/** Removes a now-acked amount from the pending total, floored at zero. */
	void clearAmount(long amount)
	{
		if (amount <= 0L || pending <= 0L)
		{
			return;
		}
		pending = Math.max(0L, pending - amount);
	}
}

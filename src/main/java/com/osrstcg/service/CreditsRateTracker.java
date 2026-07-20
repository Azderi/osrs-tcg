package com.osrstcg.service;

import java.util.ArrayDeque;
import javax.inject.Singleton;

/**
 * Sliding-window credits-per-hour from recent {@link TcgStateService#addCredits(long)} gains.
 * The displayed rate is only recomputed when a new credit drop is recorded.
 */
@Singleton
public class CreditsRateTracker
{
	private static final long WINDOW_MS = 15L * 60L * 1000L;
	private static final int MIN_DROPS_TO_SHOW = 3;

	private final ArrayDeque<CreditDrop> drops = new ArrayDeque<>();
	/** Last rate computed on a credit drop; {@code null} until {@value #MIN_DROPS_TO_SHOW} drops in-window. */
	private Long cachedCreditsPerHour;

	public synchronized void recordCreditGain(long amount)
	{
		if (amount <= 0L)
		{
			return;
		}

		long now = System.currentTimeMillis();
		drops.addLast(new CreditDrop(now, amount));
		prune(now);
		recomputeCachedRate(now);
	}

	/**
	 * @return last credits/h computed on a credit drop, or {@code null} until at least
	 * {@value #MIN_DROPS_TO_SHOW} drops are in the 15-minute window, or after 15 minutes
	 * with no new drops
	 */
	public synchronized Long creditsPerHourOrNull()
	{
		if (cachedCreditsPerHour == null || drops.isEmpty())
		{
			return null;
		}

		long now = System.currentTimeMillis();
		if (now - drops.peekLast().timeMs >= WINDOW_MS)
		{
			cachedCreditsPerHour = null;
			prune(now);
			return null;
		}

		return cachedCreditsPerHour;
	}

	public synchronized void clear()
	{
		drops.clear();
		cachedCreditsPerHour = null;
	}

	private void recomputeCachedRate(long nowMs)
	{
		if (drops.size() < MIN_DROPS_TO_SHOW)
		{
			cachedCreditsPerHour = null;
			return;
		}

		long total = 0L;
		long oldestMs = drops.peekFirst().timeMs;
		for (CreditDrop drop : drops)
		{
			total += drop.amount;
		}

		long elapsedMs = Math.max(1L, nowMs - oldestMs);
		cachedCreditsPerHour = Math.round(total * 3_600_000.0d / (double) elapsedMs);
	}

	private void prune(long nowMs)
	{
		long cutoff = nowMs - WINDOW_MS;
		while (!drops.isEmpty() && drops.peekFirst().timeMs < cutoff)
		{
			drops.removeFirst();
		}
	}

	private static final class CreditDrop
	{
		private final long timeMs;
		private final long amount;

		private CreditDrop(long timeMs, long amount)
		{
			this.timeMs = timeMs;
			this.amount = amount;
		}
	}
}

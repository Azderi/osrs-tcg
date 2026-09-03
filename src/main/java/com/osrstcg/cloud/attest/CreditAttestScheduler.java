package com.osrstcg.cloud.attest;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Timer and early-flush scheduling for {@link CreditAttestQueue}. */
final class CreditAttestScheduler
{
	private final ScheduledExecutorService scheduler;
	private final AtomicBoolean running;
	private final AtomicLong lastGoodAttestAfterMs;
	private final AtomicBoolean earlyFlushScheduled;
	private final AtomicBoolean retryFlushScheduled = new AtomicBoolean(false);
	private final long defaultAttestAfterMs;
	private final Runnable flushSafeFalse;
	private final BooleanSupplier stillRunning;
	private final Object scheduleLock = new Object();
	private ScheduledFuture<?> flushFuture;
	private ScheduledFuture<?> retryFlushFuture;

	/** @param flushSafeFalse invoked to run a non-teardown flush; {@code stillRunning} checked after each tick to decide whether to reschedule */
	CreditAttestScheduler(
		ScheduledExecutorService scheduler,
		AtomicBoolean running,
		AtomicLong lastGoodAttestAfterMs,
		AtomicBoolean earlyFlushScheduled,
		long defaultAttestAfterMs,
		Runnable flushSafeFalse,
		BooleanSupplier stillRunning)
	{
		this.scheduler = scheduler;
		this.running = running;
		this.lastGoodAttestAfterMs = lastGoodAttestAfterMs;
		this.earlyFlushScheduled = earlyFlushScheduled;
		this.defaultAttestAfterMs = defaultAttestAfterMs;
		this.flushSafeFalse = flushSafeFalse;
		this.stillRunning = stillRunning;
	}

	/** Starts the periodic flush timer at the default interval, if not already running. Idempotent. */
	void start()
	{
		synchronized (scheduleLock)
		{
			if (running.get())
			{
				return;
			}
			running.set(true);
			lastGoodAttestAfterMs.set(defaultAttestAfterMs);
			scheduleNextLocked(lastGoodAttestAfterMs.get());
		}
	}

	/** Stops the scheduler and cancels any pending periodic or retry flush. */
	void stop()
	{
		synchronized (scheduleLock)
		{
			running.set(false);
			cancelScheduledLocked();
			cancelRetryFlushLocked();
		}
	}

	/** Runs a flush immediately on the executor, coalescing concurrent requests via {@code earlyFlushScheduled}. */
	void scheduleEarlyFlush()
	{
		if (!earlyFlushScheduled.compareAndSet(false, true))
		{
			return;
		}
		scheduler.execute(() ->
		{
			try
			{
				flushSafeFalse.run();
			}
			finally
			{
				earlyFlushScheduled.set(false);
			}
		});
	}

	/**
	 * Schedules a single flush retry after {@code delayMs}, unless the scheduler is stopped or a retry
	 * is already pending (only one retry flush may be in flight at a time).
	 */
	void scheduleRetryFlush(long delayMs)
	{
		if (!running.get())
		{
			return;
		}
		if (!retryFlushScheduled.compareAndSet(false, true))
		{
			return;
		}
		synchronized (scheduleLock)
		{
			if (!running.get())
			{
				retryFlushScheduled.set(false);
				return;
			}
			retryFlushFuture = scheduler.schedule(() ->
			{
				try
				{
					flushSafeFalse.run();
				}
				finally
				{
					retryFlushScheduled.set(false);
					synchronized (scheduleLock)
					{
						retryFlushFuture = null;
					}
				}
			}, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
		}
	}

	/** Periodic timer callback: runs a flush, then reschedules itself using the latest attest-after interval. */
	void flushTick()
	{
		try
		{
			flushSafeFalse.run();
		}
		finally
		{
			synchronized (scheduleLock)
			{
				if (stillRunning.getAsBoolean())
				{
					scheduleNextLocked(lastGoodAttestAfterMs.get());
				}
			}
		}
	}

	/** Schedules the next periodic {@link #flushTick()}; must be called while holding {@link #scheduleLock}. */
	private void scheduleNextLocked(long delayMs)
	{
		if (!running.get())
		{
			return;
		}
		flushFuture = scheduler.schedule(this::flushTick, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
	}

	/** Cancels the pending periodic flush, if any; must be called while holding {@link #scheduleLock}. */
	private void cancelScheduledLocked()
	{
		if (flushFuture != null)
		{
			flushFuture.cancel(false);
			flushFuture = null;
		}
	}

	/** Cancels the pending retry flush, if any, and clears its scheduled flag; must hold {@link #scheduleLock}. */
	private void cancelRetryFlushLocked()
	{
		if (retryFlushFuture != null)
		{
			retryFlushFuture.cancel(false);
			retryFlushFuture = null;
		}
		retryFlushScheduled.set(false);
	}
}

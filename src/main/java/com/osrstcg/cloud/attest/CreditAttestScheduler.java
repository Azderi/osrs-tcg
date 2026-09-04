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
	private final AtomicBoolean rateCapPaused = new AtomicBoolean(false);
	private final long defaultAttestAfterMs;
	private final Runnable flushSafeFalse;
	private final BooleanSupplier stillRunning;
	private final Object scheduleLock = new Object();
	private ScheduledFuture<?> flushFuture;
	private ScheduledFuture<?> retryFlushFuture;
	private ScheduledFuture<?> rateCapResumeFuture;
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
			rateCapPaused.set(false);
			lastGoodAttestAfterMs.set(defaultAttestAfterMs);
			scheduleNextLocked(lastGoodAttestAfterMs.get());
		}
	}
/** Stops the scheduler and cancels any pending periodic, retry, or rate-cap resume flush. */
	void stop()
	{
		synchronized (scheduleLock)
		{
			running.set(false);
			rateCapPaused.set(false);
			flushFuture = cancel(flushFuture);
			retryFlushFuture = cancel(retryFlushFuture);
			retryFlushScheduled.set(false);
			rateCapResumeFuture = cancel(rateCapResumeFuture);
		}
	}
/** True while a server rate-cap pause is holding periodic/early/retry flushes. */
	boolean isRateCapPaused()
	{
		return rateCapPaused.get();
	}
/**
	 * Cancels periodic and retry flushes and schedules a single resume after {@code delayMs} that
	 * restarts the normal attest interval. Leaves {@code running} true. No-op if stopped.
	 */
	void pauseFor(long delayMs)
	{
		synchronized (scheduleLock)
		{
			if (!running.get())
			{
				return;
			}
			rateCapPaused.set(true);
			flushFuture = cancel(flushFuture);
			retryFlushFuture = cancel(retryFlushFuture);
			retryFlushScheduled.set(false);
			rateCapResumeFuture = cancel(rateCapResumeFuture);
			rateCapResumeFuture = scheduler.schedule(this::resumeAfterRateCap, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
		}
	}
/** Clears the rate-cap pause and reschedules the periodic flush using {@code lastGoodAttestAfterMs}. */
	private void resumeAfterRateCap()
	{
		synchronized (scheduleLock)
		{
			rateCapResumeFuture = null;
			if (!running.get())
			{
				rateCapPaused.set(false);
				return;
			}
			rateCapPaused.set(false);
			scheduleNextLocked(lastGoodAttestAfterMs.get());
		}
	}
/** Runs a flush immediately on the executor, coalescing concurrent requests via {@code earlyFlushScheduled}. */
	void scheduleEarlyFlush()
	{
		if (rateCapPaused.get() || !running.get())
		{
			return;
		}
		if (!earlyFlushScheduled.compareAndSet(false, true))
		{
			return;
		}
		scheduler.execute(() ->
		{
			try
			{
				runFlushIfActive();
			}
			finally
			{
				earlyFlushScheduled.set(false);
			}
		});
	}
/**
	 * Schedules a single flush retry after {@code delayMs}, unless the scheduler is stopped, rate-cap
	 * paused, or a retry is already pending (only one retry flush may be in flight at a time).
	 */
	void scheduleRetryFlush(long delayMs)
	{
		if (!running.get() || rateCapPaused.get())
		{
			return;
		}
		if (!retryFlushScheduled.compareAndSet(false, true))
		{
			return;
		}
		synchronized (scheduleLock)
		{
			if (!running.get() || rateCapPaused.get())
			{
				retryFlushScheduled.set(false);
				return;
			}
			retryFlushFuture = scheduler.schedule(() ->
			{
				try
				{
					runFlushIfActive();
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
			if (!rateCapPaused.get())
			{
				flushSafeFalse.run();
			}
		}
		finally
		{
			synchronized (scheduleLock)
			{
				if (stillRunning.getAsBoolean() && !rateCapPaused.get())
				{
					scheduleNextLocked(lastGoodAttestAfterMs.get());
				}
			}
		}
	}
/** Schedules the next periodic {@link #flushTick()}; must be called while holding {@link #scheduleLock}. */
	private void scheduleNextLocked(long delayMs)
	{
		if (!running.get() || rateCapPaused.get())
		{
			return;
		}
		flushFuture = scheduler.schedule(this::flushTick, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
	}

	private void runFlushIfActive()
	{
		if (!rateCapPaused.get() && running.get())
		{
			flushSafeFalse.run();
		}
	}

/** Cancels {@code future} if non-null; must hold {@link #scheduleLock}. Returns null for clearing the field. */
	private static ScheduledFuture<?> cancel(ScheduledFuture<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
		return null;
	}
}

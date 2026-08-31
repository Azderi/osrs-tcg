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

	void stop()
	{
		synchronized (scheduleLock)
		{
			running.set(false);
			cancelScheduledLocked();
			cancelRetryFlushLocked();
		}
	}

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

	private void scheduleNextLocked(long delayMs)
	{
		if (!running.get())
		{
			return;
		}
		flushFuture = scheduler.schedule(this::flushTick, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
	}

	private void cancelScheduledLocked()
	{
		if (flushFuture != null)
		{
			flushFuture.cancel(false);
			flushFuture = null;
		}
	}

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

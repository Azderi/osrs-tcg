package com.osrstcg.cloud.attest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CreditAttestSchedulerTest
{
	private ScheduledExecutorService executor;

	@Before
	public void setUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	@Test
	public void pauseForBlocksEarlyAndRetryUntilResume() throws Exception
	{
		AtomicBoolean running = new AtomicBoolean(false);
		AtomicLong lastGoodAttestAfterMs = new AtomicLong(40L);
		AtomicBoolean earlyFlushScheduled = new AtomicBoolean(false);
		AtomicInteger flushCount = new AtomicInteger(0);
		CountDownLatch resumedFlush = new CountDownLatch(1);
		AtomicReference<CreditAttestScheduler> schedulerRef = new AtomicReference<>();

		CreditAttestScheduler scheduler = new CreditAttestScheduler(
			executor,
			running,
			lastGoodAttestAfterMs,
			earlyFlushScheduled,
			60_000L,
			() ->
			{
				flushCount.incrementAndGet();
				CreditAttestScheduler s = schedulerRef.get();
				if (s != null && !s.isRateCapPaused())
				{
					resumedFlush.countDown();
				}
			},
			running::get);
		schedulerRef.set(scheduler);
		scheduler.start();
		lastGoodAttestAfterMs.set(40L);

		scheduler.pauseFor(30L);
		assertTrue(scheduler.isRateCapPaused());

		int before = flushCount.get();
		scheduler.scheduleEarlyFlush();
		scheduler.scheduleRetryFlush(0L);
		assertEquals(before, flushCount.get());

		assertTrue(resumedFlush.await(2L, TimeUnit.SECONDS));
		assertFalse(scheduler.isRateCapPaused());
		assertTrue(flushCount.get() > before);

		scheduler.stop();
	}

	@Test
	public void stopClearsRateCapPause()
	{
		AtomicBoolean running = new AtomicBoolean(false);
		AtomicLong lastGoodAttestAfterMs = new AtomicLong(60_000L);
		AtomicBoolean earlyFlushScheduled = new AtomicBoolean(false);
		CreditAttestScheduler scheduler = new CreditAttestScheduler(
			executor,
			running,
			lastGoodAttestAfterMs,
			earlyFlushScheduled,
			60_000L,
			() -> { },
			running::get);
		scheduler.start();
		scheduler.pauseFor(60_000L);
		assertTrue(scheduler.isRateCapPaused());
		scheduler.stop();
		assertFalse(scheduler.isRateCapPaused());
		assertFalse(running.get());
	}

	@Test
	public void resumeUsesLastGoodAttestAfterMsNotRateCapDelay() throws Exception
	{
		AtomicBoolean running = new AtomicBoolean(false);
		AtomicLong lastGoodAttestAfterMs = new AtomicLong(25L);
		AtomicBoolean earlyFlushScheduled = new AtomicBoolean(false);
		AtomicInteger flushCount = new AtomicInteger(0);
		CountDownLatch firstPostResumeFlush = new CountDownLatch(1);
		AtomicReference<CreditAttestScheduler> schedulerRef = new AtomicReference<>();

		CreditAttestScheduler scheduler = new CreditAttestScheduler(
			executor,
			running,
			lastGoodAttestAfterMs,
			earlyFlushScheduled,
			60_000L,
			() ->
			{
				flushCount.incrementAndGet();
				CreditAttestScheduler s = schedulerRef.get();
				if (s != null && !s.isRateCapPaused())
				{
					firstPostResumeFlush.countDown();
				}
			},
			running::get);
		schedulerRef.set(scheduler);
		scheduler.start();

		// Long rate-cap delay would keep us paused if mistakenly used as the steady interval.
		lastGoodAttestAfterMs.set(25L);
		scheduler.pauseFor(20L);

		assertTrue(firstPostResumeFlush.await(2L, TimeUnit.SECONDS));
		assertFalse(scheduler.isRateCapPaused());
		assertEquals(25L, lastGoodAttestAfterMs.get());

		scheduler.stop();
	}
}

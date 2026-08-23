package com.osrstcg.credit;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.config.CreditsPerHourWindow;
import java.util.ArrayDeque;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrstcg.state.TcgStateService;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Credits-per-hour from {@link TcgStateService} credit gains.
 * Uses a configurable sliding window ({@link OsrsTcgConfig#creditsPerHourWindow()});
 * {@link CreditsPerHourWindow#PERSISTENT} keeps all drops until {@link #clear()}.
 * The displayed rate is only recomputed when a new credit drop is recorded.
 */
@Singleton
public class CreditsRateTracker
{
	private static final int MIN_DROPS_TO_SHOW = 3;

	private final OsrsTcgConfig config;
	private final ArrayDeque<CreditDrop> drops = new ArrayDeque<>();
	/** Last rate computed on a credit drop; {@code null} until {@value #MIN_DROPS_TO_SHOW} drops in-window. */
	private Long cachedCreditsPerHour;

	@Inject
	CreditsRateTracker(OsrsTcgConfig config)
	{
		this.config = config;
	}

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
	 * {@value #MIN_DROPS_TO_SHOW} drops are in the window, or after the window elapses
	 * with no new drops (timed modes only)
	 */
	public synchronized Long creditsPerHourOrNull()
	{
		if (cachedCreditsPerHour == null || drops.isEmpty())
		{
			return null;
		}

		Long windowMs = windowMsOrNull();
		if (windowMs != null)
		{
			long now = System.currentTimeMillis();
			if (now - drops.peekLast().timeMs >= windowMs)
			{
				cachedCreditsPerHour = null;
				prune(now);
				return null;
			}
		}

		return cachedCreditsPerHour;
	}

	public synchronized void clear()
	{
		drops.clear();
		cachedCreditsPerHour = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event != null && event.getGameState() == GameState.LOGIN_SCREEN)
		{
			clear();
		}
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
		Long windowMs = windowMsOrNull();
		if (windowMs == null)
		{
			return;
		}

		long cutoff = nowMs - windowMs;
		while (!drops.isEmpty() && drops.peekFirst().timeMs < cutoff)
		{
			drops.removeFirst();
		}
	}

	private Long windowMsOrNull()
	{
		CreditsPerHourWindow window = config.creditsPerHourWindow();
		if (window == null)
		{
			return CreditsPerHourWindow.PERSISTENT.getWindowMs();
		}
		return window.getWindowMs();
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

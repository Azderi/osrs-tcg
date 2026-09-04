package com.osrstcg.credit;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.config.CreditsPerHourWindow;
import java.util.ArrayDeque;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
/**
 * Tracks recent credit gains and derives a rolling credits-per-hour rate for the sidebar. Subscribes to
 * {@link GameStateChanged} to reset on logout. All public methods are synchronized since drops can be
 * recorded from background threads while the UI reads the rate on the client/EDT thread.
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
/** Records a credit gain (credits) at the current time, pruning old drops and recomputing the cached rate. */
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
/** Current credits/hour rate, or {@code null} if too few recent drops or the window has gone stale. */
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
/** Discards all tracked drops and the cached rate. */
	public synchronized void clear()
	{
		drops.clear();
		cachedCreditsPerHour = null;
	}
/** Resets tracking when the player returns to the login screen. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event != null && event.getGameState() == GameState.LOGIN_SCREEN)
		{
			clear();
		}
	}
/** Recomputes {@link #cachedCreditsPerHour} from the current drop window, or clears it if below the minimum. */
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
/** Removes drops older than the configured rate window (no-op if the window is unbounded). */
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
/** Configured rate window in ms, or {@code null} for the persistent (unbounded) window. */
	private Long windowMsOrNull()
	{
		CreditsPerHourWindow window = config.creditsPerHourWindow();
		if (window == null)
		{
			return CreditsPerHourWindow.PERSISTENT.getWindowMs();
		}
		return window.getWindowMs();
	}
/** A single recorded credit gain: {@code amount} credits at {@code timeMs}. */
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

package com.osrstcg.cloud.session;

import com.osrstcg.cloud.api.CloudConnectionState;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.trade.TradeCloudService;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.SidebarRefresh;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WorldChanged;
/**
 * Wires {@link CloudSessionService} connection lifecycle to RuneLite client events: connects on
 * login/world change, disconnects on logout, and schedules randomized-delay reconnect attempts
 * while cloud is reachable-but-not-ready. Starts/stops the credit attest queue and trade sync
 * alongside session readiness, and pauses all cloud traffic on restricted world types. Connection
 * attempts run on the injected {@link ScheduledExecutorService}; UI refresh callbacks are marshalled
 * onto the EDT via {@link SwingUtilities#invokeLater}.
 */
@Slf4j
@Singleton
public class CloudSessionCoordinator
{
	private static final long CLOUD_RECONNECT_MIN_MS = 5L * 60L * 1000L;
	private static final long CLOUD_RECONNECT_MAX_MS = 15L * 60L * 1000L;

	private final Client client;
	private final TcgStateService stateService;
	private final CloudSessionService cloudSessionService;
	private final CreditAttestQueue creditAttestQueue;
	private final TradeCloudService tradeCloudService;
	private final SidebarRefresh sidebarRefresh;
	private final ScheduledExecutorService scheduler;

	private final AtomicBoolean cloudConnectInFlight = new AtomicBoolean(false);
	private final Object cloudReconnectLock = new Object();
	private ScheduledFuture<?> cloudReconnectFuture;
	private GameState lastObservedGameState;
/** Wires collaborators; no side effects. */
	@Inject
	public CloudSessionCoordinator(
		Client client,
		TcgStateService stateService,
		CloudSessionService cloudSessionService,
		CreditAttestQueue creditAttestQueue,
		TradeCloudService tradeCloudService,
		SidebarRefresh sidebarRefresh,
		ScheduledExecutorService scheduler)
	{
		this.client = client;
		this.stateService = stateService;
		this.cloudSessionService = cloudSessionService;
		this.creditAttestQueue = creditAttestQueue;
		this.tradeCloudService = tradeCloudService;
		this.sidebarRefresh = sidebarRefresh;
		this.scheduler = scheduler;
	}
/**
	 * Registers a listener on {@link CloudSessionService} that starts/stops the attest queue and
	 * trade sync in response to readiness changes, manages reconnect scheduling, and refreshes the
	 * sidebar on every status change.
	 */
	public void installStatusListener()
	{
		cloudSessionService.setStatusListener(() ->
		{
			if (cloudSessionService.isReady())
			{
				cancelReconnect();
				creditAttestQueue.start();
				tradeCloudService.start();
				creditAttestQueue.flushNow();
			}
			else if (!cloudSessionService.canCollectAttests())
			{
				cancelReconnect();
			}
			else
			{
				scheduleReconnectIfNeeded();
			}
			sidebarRefresh.refresh();
		});
	}
/** Removes the status listener installed by {@link #installStatusListener()}. */
	public void clearStatusListener()
	{
		cloudSessionService.setStatusListener(null);
	}
/**
	 * Kicks off (or no-ops if already in flight) an async attempt to establish/verify the cloud
	 * session on {@link #scheduler}. Short-circuits if the account is locked or the world is
	 * restricted. On success, cancels any pending reconnect and starts the attest queue and trade
	 * sync; on failure, schedules a reconnect.
	 */
	public void connect()
	{
		if (cloudSessionService.isAccountLocked())
		{
			cancelReconnect();
			SwingUtilities.invokeLater(sidebarRefresh::refresh);
			return;
		}
		if (cloudSessionService.isRestrictedWorld())
		{
			pauseForRestrictedWorld();
			return;
		}
		if (!cloudConnectInFlight.compareAndSet(false, true))
		{
			return;
		}
		scheduler.execute(() ->
		{
			try
			{
				if (cloudSessionService.isAccountLocked())
				{
					cancelReconnect();
					SwingUtilities.invokeLater(sidebarRefresh::refresh);
					return;
				}
				if (cloudSessionService.isRestrictedWorld())
				{
					pauseForRestrictedWorld();
					return;
				}
				cloudSessionService.ensureSession();
				if (cloudSessionService.isReady())
				{
					cancelReconnect();
					creditAttestQueue.start();
					tradeCloudService.start();
					creditAttestQueue.flushNow();
				}
				else
				{
					scheduleReconnectIfNeeded();
				}
			}
			finally
			{
				cloudConnectInFlight.set(false);
			}
		});
	}
/** Cancels reconnect, stops attest/trade traffic, and marks the session as restricted-world-paused. */
	public void pauseForRestrictedWorld()
	{
		cancelReconnect();
		creditAttestQueue.stop();
		tradeCloudService.stop();
		cloudSessionService.enterRestrictedWorld();
		SwingUtilities.invokeLater(sidebarRefresh::refresh);
	}
/**
	 * Tears down the cloud session on logout. Cancels hiscores settle first (so no settle-hiscores
	 * can fire during teardown). If the account is locked, discards pending attests without flushing;
	 * otherwise blocks flushing pending attests before disconnecting.
	 */
	public void disconnect()
	{
		cancelReconnect();
		cloudSessionService.cancelHiscoresSettle();
		if (cloudSessionService.isAccountLocked())
		{
			creditAttestQueue.stop();
			creditAttestQueue.discardPending();
			tradeCloudService.stop();
			cloudSessionService.disconnectQuietly();
			return;
		}
		creditAttestQueue.flushBlocking();
		creditAttestQueue.stop();
		tradeCloudService.stop();
		cloudSessionService.disconnectQuietly();
	}
/**
	 * Schedules a single reconnect attempt with a random delay between {@link #CLOUD_RECONNECT_MIN_MS}
	 * and {@link #CLOUD_RECONNECT_MAX_MS}, unless one is already pending, reconnect isn't applicable
	 * (not logged in, can't collect attests, already ready, or waiting on game identity), or the
	 * current connection state isn't {@code ERROR}/{@code DISCONNECTED}.
	 */
	public void scheduleReconnectIfNeeded()
	{
		if (client.getGameState() != GameState.LOGGED_IN
			|| !cloudSessionService.canCollectAttests()
			|| cloudSessionService.isReady()
			|| cloudSessionService.isWaitingForGameIdentity())
		{
			cancelReconnect();
			return;
		}
		CloudConnectionState state = cloudSessionService.getConnectionState();
		if (state != CloudConnectionState.ERROR && state != CloudConnectionState.DISCONNECTED)
		{
			return;
		}
		long spanMs = CLOUD_RECONNECT_MAX_MS - CLOUD_RECONNECT_MIN_MS;
		long delayMs = CLOUD_RECONNECT_MIN_MS
			+ ThreadLocalRandom.current().nextLong(spanMs + 1L);
		synchronized (cloudReconnectLock)
		{
			if (cloudReconnectFuture != null && !cloudReconnectFuture.isDone())
			{
				return;
			}
			cloudReconnectFuture = scheduler.schedule(
				this::onReconnectTimer,
				delayMs,
				TimeUnit.MILLISECONDS);
		}
		cloudSessionService.noteOfflineReconnectScheduled();
	}
/** Fires when a scheduled reconnect delay elapses; re-attempts {@link #connect()} if still needed. */
	private void onReconnectTimer()
	{
		synchronized (cloudReconnectLock)
		{
			cloudReconnectFuture = null;
		}
		if (client.getGameState() != GameState.LOGGED_IN
			|| !cloudSessionService.canCollectAttests()
			|| cloudSessionService.isReady())
		{
			return;
		}
		connect();
	}
/** Cancels any pending scheduled reconnect, if one exists. */
	public void cancelReconnect()
	{
		synchronized (cloudReconnectLock)
		{
			if (cloudReconnectFuture != null)
			{
				cloudReconnectFuture.cancel(false);
				cloudReconnectFuture = null;
			}
		}
	}
/**
	 * Per-tick hook: if logged in, not locked, not already connecting/active, and still waiting for
	 * game identity (display name/account hash) to become available, retries {@link #connect()}.
	 */
	public void onLoggedInGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (cloudSessionService.isAccountLocked())
		{
			return;
		}
		if (cloudSessionService.isSessionActive() || cloudConnectInFlight.get())
		{
			return;
		}
		if (cloudSessionService.isWaitingForGameIdentity())
		{
			connect();
		}
	}
/**
	 * Reacts to RuneLite game state transitions: flushes pending attests on leaving a logged-in
	 * session (excluding hop/loading transitions), disconnects on reaching the login screen, and
	 * connects (or resumes a restricted-world pause) on reaching logged-in.
	 */
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState gs = event.getGameState();
		GameState previous = lastObservedGameState;
		lastObservedGameState = gs;

		if (previous == GameState.LOGGED_IN
			&& gs != GameState.LOGGED_IN
			&& gs != GameState.HOPPING
			&& gs != GameState.LOADING)
		{
			creditAttestQueue.flushNow();
		}

		if (gs == GameState.LOGIN_SCREEN)
		{
			disconnect();
		}
		else if (gs == GameState.LOGGED_IN)
		{
			if (previous == GameState.LOADING && cloudSessionService.isSessionActive())
			{
				if (cloudSessionService.isRestrictedWorld())
				{
					pauseForRestrictedWorld();
				}
			}
			else
			{
				connect();
			}
		}
	}
/** Re-attempts connect on world hop (world type may have changed) and refreshes the sidebar. */
	public void onWorldChanged(WorldChanged event)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			connect();
		}
		SwingUtilities.invokeLater(sidebarRefresh::refresh);
	}
/**
	 * Client-shutdown hook: cancels hiscores settle, then blocking-flushes pending attests (unless
	 * the account is locked), logging rather than throwing on failure, then always stops
	 * attest/trade traffic and disconnects.
	 */
	public void flushAttestsForShutdown()
	{
		cloudSessionService.cancelHiscoresSettle();
		try
		{
			if (!cloudSessionService.isAccountLocked())
			{
				creditAttestQueue.flushBlocking();
			}
		}
		catch (Exception e)
		{
			log.warn("Credit attest flush on client shutdown failed", e);
		}
		finally
		{
			creditAttestQueue.stop();
			tradeCloudService.stop();
			cloudSessionService.disconnectQuietly();
		}
	}
}

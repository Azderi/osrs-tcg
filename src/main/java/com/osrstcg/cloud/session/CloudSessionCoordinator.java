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
 * Connect / disconnect / restricted-world pause / offline reconnect timer / shutdown attest flush.
 * {@link com.osrstcg.OsrsTcgPlugin} keeps {@code @Subscribe} dispatch.
 */
@Slf4j
@Singleton
public class CloudSessionCoordinator
{
	/** Minimum delay while consented-but-offline before retrying {@link #connect()}. */
	private static final long CLOUD_RECONNECT_MIN_DELAY_MS = 5L * 60L * 1000L;
	/** Maximum delay (inclusive upper bound via jitter) for offline reconnect attempts. */
	private static final long CLOUD_RECONNECT_MAX_DELAY_MS = 15L * 60L * 1000L;

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

	public void clearStatusListener()
	{
		cloudSessionService.setStatusListener(null);
	}

	/** Kicks off pairing/refresh + starts the attest queue and trade poll once connected. */
	public void connect()
	{
		if (cloudSessionService.isAccountLocked())
		{
			cancelReconnect();
			SwingUtilities.invokeLater(sidebarRefresh::refresh);
			return;
		}
		if (stateService.isDebugLogging())
		{
			cancelReconnect();
			cloudSessionService.pauseForDebugMode();
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
				if (stateService.isDebugLogging())
				{
					cancelReconnect();
					cloudSessionService.pauseForDebugMode();
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

	/** Stop cloud traffic and show yellow status while on a blocked world type. */
	public void pauseForRestrictedWorld()
	{
		cancelReconnect();
		creditAttestQueue.stop();
		tradeCloudService.stop();
		cloudSessionService.enterRestrictedWorld();
		SwingUtilities.invokeLater(sidebarRefresh::refresh);
	}

	public void disconnect()
	{
		cancelReconnect();
		if (cloudSessionService.isAccountLocked())
		{
			creditAttestQueue.stop();
			creditAttestQueue.discardPending();
			tradeCloudService.stop();
			cloudSessionService.disconnectQuietly();
			return;
		}
		creditAttestQueue.flushBlocking();
		cloudSessionService.snapshotHiscoresOnLogout();
		creditAttestQueue.stop();
		tradeCloudService.stop();
		cloudSessionService.disconnectQuietly();
	}

	/**
	 * After consent, while offline (and not banned/quarantined), retry {@link #connect()}
	 * after a uniform delay in [{@link #CLOUD_RECONNECT_MIN_DELAY_MS}, {@link #CLOUD_RECONNECT_MAX_DELAY_MS}].
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
		long spanMs = CLOUD_RECONNECT_MAX_DELAY_MS - CLOUD_RECONNECT_MIN_DELAY_MS;
		long delayMs = CLOUD_RECONNECT_MIN_DELAY_MS
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

	/** Retry pairing until local player identity is ready. */
	public void onLoggedInGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (stateService.isDebugLogging())
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

	public void onWorldChanged(WorldChanged event)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			connect();
		}
		SwingUtilities.invokeLater(sidebarRefresh::refresh);
	}

	/** Best-effort coalesce→attest drain used from ClientShutdown's waited Future. */
	public void flushAttestsForShutdown()
	{
		try
		{
			if (!cloudSessionService.isAccountLocked())
			{
				creditAttestQueue.flushBlocking();
				cloudSessionService.snapshotHiscoresOnLogout();
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

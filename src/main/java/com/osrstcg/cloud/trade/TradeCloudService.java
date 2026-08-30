package com.osrstcg.cloud.trade;

import com.google.gson.JsonObject;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.LinkBrowser;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.cloud.session.CloudSessionService;

/**
 * Single cloud timer: trade inbox + piggybacked sidebar stats, with collection
 * reconcile when local counts disagree with inbox overview (or sync markers diverge).
 * Next delay comes only from {@code pollAfterMs}. World hops must not restart
 * a faster fixed schedule - {@link #start()} is idempotent while already running.
 */
@Slf4j
@Singleton
public final class TradeCloudService
{
	/** Default when the server omits pollAfterMs (matches low-load floor). */
	private static final long DEFAULT_POLL_MS = 15_000L;
	/**
	 * Floor only for missing/invalid success values; server {@code pollAfterMs} is otherwise
	 * authoritative (no client max). Error backoff still uses this as a lower bound.
	 */
	private static final long MIN_POLL_MS = 15_000L;
	private static final long BACKOFF_INITIAL_MS = 15_000L;
	private static final long BACKOFF_MAX_MS = 180_000L;
	private static final long AUTH_RETRY_MS = 60_000L;

	private final CloudApiClient api;
	private final CloudSessionService session;
	private final Client client;
	private final ChatMessageManager chatMessageManager;
	private final ScheduledExecutorService scheduler;

	private final AtomicReference<TradeInboxItem> pendingAccept = new AtomicReference<>(null);
	private final AtomicReference<Runnable> inboxListener = new AtomicReference<>(null);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean polling = new AtomicBoolean(false);
	private final AtomicBoolean forceAgain = new AtomicBoolean(false);
	/** First inbox poll after {@link #start()} broadcasts pending trades in chat. */
	private final AtomicBoolean broadcastPendingOnLogin = new AtomicBoolean(false);
	private final AtomicLong lastRevision = new AtomicLong(-1L);
	private final AtomicLong lastGoodPollAfterMs = new AtomicLong(DEFAULT_POLL_MS);
	private final AtomicLong backoffMs = new AtomicLong(0L);
	private final Object scheduleLock = new Object();
	private ScheduledFuture<?> pollFuture;

	@Inject
	TradeCloudService(
		CloudApiClient api,
		CloudSessionService session,
		Client client,
		ChatMessageManager chatMessageManager,
		ScheduledExecutorService scheduler)
	{
		this.api = api;
		this.session = session;
		this.client = client;
		this.chatMessageManager = chatMessageManager;
		this.scheduler = scheduler;
	}

	/**
	 * Start inbox polling. Idempotent while already running so world hops do not reset
	 * {@code sinceRevision} or force a faster timer. Fresh start (after logout) triggers an
	 * immediate poll; subsequent delays come from server {@code pollAfterMs}.
	 */
	public void start()
	{
		synchronized (scheduleLock)
		{
			if (running.get())
			{
				return;
			}
			running.set(true);
			lastRevision.set(-1L);
			backoffMs.set(0L);
			lastGoodPollAfterMs.set(DEFAULT_POLL_MS);
			broadcastPendingOnLogin.set(true);
			scheduleNextLocked(0L);
		}
	}

	public void stop()
	{
		synchronized (scheduleLock)
		{
			running.set(false);
			forceAgain.set(false);
			cancelScheduledLocked();
			lastRevision.set(-1L);
			backoffMs.set(0L);
			broadcastPendingOnLogin.set(false);
		}
	}

	/**
	 * Immediate inbox poll (e.g. after pack / attest / trade create). Next delay still
	 * comes from the server {@code pollAfterMs} on the following successful response.
	 */
	public void requestForcedRefresh()
	{
		synchronized (scheduleLock)
		{
			if (!running.get())
			{
				return;
			}
			if (polling.get())
			{
				forceAgain.set(true);
				return;
			}
			cancelScheduledLocked();
			scheduleNextLocked(0L);
		}
	}

	/** Keep {@code sinceRevision} aligned after pack / attest RPC responses. */
	public void noteRevision(long revision)
	{
		if (revision >= 0L)
		{
			lastRevision.set(revision);
		}
	}

	public long getLastRevision()
	{
		return lastRevision.get();
	}

	public void setInboxListener(Runnable listener)
	{
		inboxListener.set(listener);
	}

	public TradeInboxItem getPendingAccept()
	{
		return pendingAccept.get();
	}

	public void sendTradeRequest(String partnerDisplayName)
	{
		if (!session.isReady())
		{
			TcgPluginGameMessages.queueGameMessage(chatMessageManager, "[OSRS TCG] Cloud offline - cannot trade.");
			return;
		}
		if (partnerDisplayName == null || partnerDisplayName.trim().isEmpty())
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			TcgPluginGameMessages.queueGameMessage(chatMessageManager,
				"[OSRS TCG] Waiting for account - try again in a moment.");
			return;
		}
		final String partner = partnerDisplayName.trim();
		scheduler.execute(() ->
		{
			try
			{
				JsonObject result = api.createTrade(partner, accountHash);
				applyEconomyFieldsFromRpc(result);
				String url = result.has("url") ? result.get("url").getAsString() : null;
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Trade request sent to " + partner
						+ (url == null || url.isEmpty() ? "." : " - finish on the website."));
				if (url != null && !url.isEmpty())
				{
					openUrl(CloudEndpoints.rewriteToWebBase(url));
				}
				notifyListener();
				requestForcedRefresh();
			}
			catch (CloudApiException ex)
			{
				queueTradeFailure(ex);
			}
			catch (IllegalArgumentException ex)
			{
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Trade failed: account hash missing - try relogging.");
			}
			catch (Exception ex)
			{
				log.warn("Trade create failed", ex);
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Trade failed - cloud error.");
			}
		});
	}

	/**
	 * Cancel a pending trade (plugin). Allowed while quarantined - frees escrow/locks.
	 */
	public void cancelTrade(String tradeId)
	{
		if (!session.isReady())
		{
			TcgPluginGameMessages.queueGameMessage(chatMessageManager, "[OSRS TCG] Cloud offline - cannot cancel trade.");
			return;
		}
		if (tradeId == null || tradeId.isBlank())
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			TcgPluginGameMessages.queueGameMessage(chatMessageManager,
				"[OSRS TCG] Waiting for account - try again in a moment.");
			return;
		}
		final String id = tradeId.trim();
		scheduler.execute(() ->
		{
			try
			{
				JsonObject result = api.cancelTrade(id, accountHash);
				applyEconomyFieldsFromRpc(result);
				TcgPluginGameMessages.queueGameMessage(chatMessageManager, "[OSRS TCG] Trade cancelled.");
				notifyListener();
				requestForcedRefresh();
			}
			catch (CloudApiException ex)
			{
				queueTradeFailure(ex);
			}
			catch (IllegalArgumentException ex)
			{
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Trade failed: account hash missing - try relogging.");
			}
			catch (Exception ex)
			{
				log.warn("Trade cancel failed", ex);
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Trade cancel failed - cloud error.");
			}
		});
	}

	private void queueTradeFailure(CloudApiException ex)
	{
		if (ex != null && (ex.isAccountBanned() || ex.isAccountQuarantined() || session.isAccountLocked()))
		{
			return;
		}
		String mapped = TradeMutationErrors.messageFor(ex);
		TcgPluginGameMessages.queueGameMessage(chatMessageManager,
			"[OSRS TCG] " + (mapped == null ? "Trade failed." : mapped));
	}

	private void scheduleNextLocked(long delayMs)
	{
		if (!running.get())
		{
			return;
		}
		pollFuture = scheduler.schedule(this::pollSafe, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
	}

	private void cancelScheduledLocked()
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(false);
			pollFuture = null;
		}
	}

	private void pollSafe()
	{
		polling.set(true);
		long nextDelayMs = lastGoodPollAfterMs.get();
		try
		{
			nextDelayMs = poll();
			backoffMs.set(0L);
			lastGoodPollAfterMs.set(resolveSuccessPollMs(nextDelayMs));
			nextDelayMs = lastGoodPollAfterMs.get();
		}
		catch (CloudApiException ex)
		{
			nextDelayMs = delayForApiError(ex);
			log.debug("Trade inbox poll failed: {} {}", ex.getStatus(), ex.getCode());
		}
		catch (Exception e)
		{
			nextDelayMs = delayForTransientError();
			log.debug("Trade inbox poll failed", e);
		}
		finally
		{
			polling.set(false);
			synchronized (scheduleLock)
			{
				if (!running.get())
				{
					return;
				}
				if (forceAgain.compareAndSet(true, false))
				{
					scheduleNextLocked(0L);
				}
				else
				{
					scheduleNextLocked(nextDelayMs);
				}
			}
		}
	}

	/**
	 * @return {@code pollAfterMs} from the server (or default when omitted)
	 */
	private long poll() throws Exception
	{
		if (!session.isReady())
		{
			return lastGoodPollAfterMs.get();
		}
		long hash = client.getAccountHash();
		if (hash == -1L)
		{
			return lastGoodPollAfterMs.get();
		}

		long knownRevision = lastRevision.get();
		Long since = knownRevision >= 0L ? knownRevision : null;
		JsonObject response = api.getTradeInbox(hash, since);

		long pollAfterMs = response.has("pollAfterMs") && !response.get("pollAfterMs").isJsonNull()
			? response.get("pollAfterMs").getAsLong()
			: DEFAULT_POLL_MS;

		boolean statsUnchanged = response.has("statsUnchanged")
			&& !response.get("statsUnchanged").isJsonNull()
			&& response.get("statsUnchanged").getAsBoolean();
		if (!statsUnchanged && response.has("stats") && response.get("stats").isJsonObject())
		{
			JsonObject stats = response.getAsJsonObject("stats");
			session.applySidebarStats(stats);
			session.maybeReconcileCollectionFromInbox(stats);
		}
		// statsUnchanged == true → keep cached sidebar stats; do not clear them.

		if (response.has("revision") && !response.get("revision").isJsonNull())
		{
			lastRevision.set(response.get("revision").getAsLong());
		}

		List<TradeInboxItem> inbox = api.parseInbox(response);
		boolean loginBroadcast = broadcastPendingOnLogin.compareAndSet(true, false);
		TradeInboxItem newest = null;
		for (TradeInboxItem item : inbox)
		{
			if (loginBroadcast || !item.isNotified())
			{
				queuePendingTradeRequestMessage(item.getFromDisplayName());
				if (!item.isNotified())
				{
					try
					{
						api.ackTradeNotify(item.getTradeId(), hash);
					}
					catch (CloudApiException ackEx)
					{
						log.debug("Trade notify ack failed: {} {}", ackEx.getCode(), ackEx.getMessage());
					}
				}
			}
			newest = item;
		}
		pendingAccept.set(newest);

		notifyListener();
		return pollAfterMs;
	}

	private void queuePendingTradeRequestMessage(String fromDisplayName)
	{
		String fromLabel = fromDisplayName == null || fromDisplayName.isBlank()
			? "someone"
			: fromDisplayName.trim();
		TcgPluginGameMessages.queueGameMessage(chatMessageManager,
			"[OSRS TCG] You have a pending trade request from " + fromLabel + ". Check the sidebar!");
	}

	private void applyEconomyFieldsFromRpc(JsonObject response)
	{
		if (response == null)
		{
			return;
		}
		// Do not pass through arbitrary "status" into account status.
		if (response.has("credits") || response.has("openedPacks") || response.has("totalCreditsGained"))
		{
			JsonObject economy = new JsonObject();
			if (response.has("credits"))
			{
				economy.add("credits", response.get("credits"));
			}
			if (response.has("openedPacks"))
			{
				economy.add("openedPacks", response.get("openedPacks"));
			}
			if (response.has("totalCreditsGained"))
			{
				economy.add("totalCreditsGained", response.get("totalCreditsGained"));
			}
			session.applySidebarStats(economy);
		}
		if (response.has("revision") && !response.get("revision").isJsonNull())
		{
			noteRevision(response.get("revision").getAsLong());
		}
	}

	private void notifyListener()
	{
		Runnable listener = inboxListener.get();
		if (listener != null)
		{
			listener.run();
		}
	}

	private long delayForApiError(CloudApiException ex)
	{
		if (ex.isUnauthorized())
		{
			// requestAuthed already attempted refresh; avoid tight-looping inbox.
			try
			{
				session.ensureSession();
			}
			catch (Exception ignored)
			{
				// ensureSession is synchronized and self-contained
			}
			return AUTH_RETRY_MS;
		}
		if (ex.isRateLimited() || ex.isServerError())
		{
			long next = backoffMs.get();
			if (next <= 0L)
			{
				next = BACKOFF_INITIAL_MS;
			}
			else
			{
				next = Math.min(BACKOFF_MAX_MS, next * 2L);
			}
			backoffMs.set(next);
			return Math.max(MIN_POLL_MS, next);
		}
		return Math.max(MIN_POLL_MS, lastGoodPollAfterMs.get());
	}

	private long delayForTransientError()
	{
		long next = backoffMs.get();
		if (next <= 0L)
		{
			next = BACKOFF_INITIAL_MS;
		}
		else
		{
			next = Math.min(BACKOFF_MAX_MS, next * 2L);
		}
		backoffMs.set(next);
		return Math.max(MIN_POLL_MS, next);
	}

	/**
	 * Server {@code pollAfterMs} is authoritative (no client max). Non-positive values
	 * fall back to the default floor.
	 */
	private static long resolveSuccessPollMs(long pollAfterMs)
	{
		if (pollAfterMs <= 0L)
		{
			return DEFAULT_POLL_MS;
		}
		return pollAfterMs;
	}

	private static void openUrl(String url)
	{
		if (url == null || url.isBlank())
		{
			return;
		}
		LinkBrowser.browse(url);
	}
}

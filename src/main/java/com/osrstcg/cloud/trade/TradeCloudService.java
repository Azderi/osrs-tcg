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
 * Manages player-to-player trading against the cloud API: sending trade requests and self-rescheduling
 * polling of the trade inbox, with backoff on errors. {@link #start()}/{@link #stop()} control the poll
 * loop's lifecycle (e.g. on login/logout); {@link #sendTradeRequest(String)} and the poll loop both run
 * their network calls on the injected {@link ScheduledExecutorService}, never on the client thread, and
 * notify the registered inbox listener afterward so the caller can hop back to the client thread.
 */
@Slf4j
@Singleton
public final class TradeCloudService
{
	private static final long DEFAULT_POLL_MS = 15_000L;
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
	private final AtomicBoolean broadcastPendingOnLogin = new AtomicBoolean(false);
	private final AtomicLong lastRevision = new AtomicLong(-1L);
	private final AtomicLong lastGoodPollAfterMs = new AtomicLong(DEFAULT_POLL_MS);
	private final AtomicLong backoffMs = new AtomicLong(0L);
	private final Object scheduleLock = new Object();
	private ScheduledFuture<?> pollFuture;

	/** Wires cloud/session collaborators and the executor used for network calls and polling. */
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

	/** Starts the inbox poll loop (idempotent) and resets revision/backoff state, forcing a login broadcast. */
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

	/** Stops the inbox poll loop and cancels any scheduled poll. */
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
	 * Requests an immediate inbox poll, bypassing the current backoff/interval. If a poll is already in
	 * flight, defers the extra poll until it finishes rather than running two concurrently.
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

	/** Records the latest known trade-inbox revision, ignoring negative (unknown) values. */
	public void noteRevision(long revision)
	{
		if (revision >= 0L)
		{
			lastRevision.set(revision);
		}
	}

	/** @return the last known trade-inbox revision, or -1 if none has been observed yet */
	public long getLastRevision()
	{
		return lastRevision.get();
	}

	/** Registers the callback invoked after each poll/mutation that may have changed inbox state. */
	public void setInboxListener(Runnable listener)
	{
		inboxListener.set(listener);
	}

	/** @return the most recent unresolved incoming trade from the last poll, or null if none */
	public TradeInboxItem getPendingAccept()
	{
		return pendingAccept.get();
	}

	/**
	 * Creates a trade request to {@code partnerDisplayName} via the cloud API. Dispatches the (blocking)
	 * network call on the executor, so this method returns immediately; chat feedback, opening the trade
	 * web page and a forced inbox refresh happen asynchronously once the call completes.
	 */
	public void sendTradeRequest(String partnerDisplayName)
	{
		if (!session.isReady())
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, "Cloud offline - cannot trade.");
			return;
		}
		if (partnerDisplayName == null || partnerDisplayName.trim().isEmpty())
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				"Waiting for account - try again in a moment.");
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
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Trade request sent to " + partner
						+ (url == null || url.isEmpty() ? "." : " - finish on the website."));
				if (url != null && !url.isEmpty())
				{
					String browseUrl = CloudEndpoints.rewriteToWebBase(url);
					if (browseUrl != null && !browseUrl.isBlank())
					{
						LinkBrowser.browse(browseUrl);
					}
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
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Trade failed: account hash missing - try relogging.");
			}
			catch (Exception ex)
			{
				log.warn("Trade create failed", ex);
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Trade failed - cloud error.");
			}
		});
	}

	/** Chats a mapped failure message for a failed trade mutation, unless the account is locked/banned/quarantined. */
	private void queueTradeFailure(CloudApiException ex)
	{
		if (ex != null && (ex.isAccountBanned() || ex.isAccountQuarantined() || session.isAccountLocked()))
		{
			return;
		}
		String mapped = TradeMutationErrors.messageFor(ex);
		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
			mapped == null ? "Trade failed." : mapped);
	}

	/** Schedules the next poll after {@code delayMs}; no-op if stopped. Caller must hold {@link #scheduleLock}. */
	private void scheduleNextLocked(long delayMs)
	{
		if (!running.get())
		{
			return;
		}
		pollFuture = scheduler.schedule(this::pollSafe, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
	}

	/** Cancels any pending scheduled poll. Caller must hold {@link #scheduleLock}. */
	private void cancelScheduledLocked()
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(false);
			pollFuture = null;
		}
	}

	/**
	 * Poll-loop entry point: runs {@link #poll()}, computes the next delay (honoring server-suggested
	 * interval, error backoff, or a queued forced refresh), and reschedules itself. Never throws - all
	 * poll failures are caught, logged and turned into a backoff delay.
	 */
	private void pollSafe()
	{
		polling.set(true);
		long nextDelayMs = lastGoodPollAfterMs.get();
		try
		{
			nextDelayMs = poll();
			backoffMs.set(0L);
			lastGoodPollAfterMs.set(nextDelayMs <= 0L ? DEFAULT_POLL_MS : nextDelayMs);
			nextDelayMs = lastGoodPollAfterMs.get();
		}
		catch (CloudApiException ex)
		{
			nextDelayMs = delayForApiError(ex);
			log.debug("Trade inbox poll failed: {} {}", ex.getStatus(), ex.getCode());
		}
		catch (Exception e)
		{
			nextDelayMs = nextBackoffDelayMs();
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
	 * Makes one blocking call to the trade-inbox endpoint, applies any changed sidebar stats/revision, chats
	 * a ping for newly-notified (or, on first poll after login, all) pending trades, acks notified items,
	 * and updates {@link #pendingAccept}.
	 *
	 * @return the server-suggested delay in ms before the next poll
	 */
	private long poll() throws Exception
	{
		if (!session.isReady() || client.getAccountHash() == -1L)
		{
			return lastGoodPollAfterMs.get();
		}

		long hash = client.getAccountHash();
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
			session.reconcileCollectionFromInbox(stats);
		}

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
				String fromLabel = item.getFromDisplayName() == null || item.getFromDisplayName().isBlank()
					? "someone"
					: item.getFromDisplayName().trim();
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"You have a pending trade request from " + fromLabel + ". Check the sidebar!");
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

	/** Applies any credits/openedPacks/totalCreditsGained/revision fields present on a trade RPC response to session state. */
	private void applyEconomyFieldsFromRpc(JsonObject response)
	{
		if (response == null)
		{
			return;
		}
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

	/** Invokes the registered inbox listener, if any. */
	private void notifyListener()
	{
		Runnable listener = inboxListener.get();
		if (listener != null)
		{
			listener.run();
		}
	}

	/**
	 * Chooses the next poll delay for a failed poll based on the error type: re-establishes the session and
	 * waits {@link #AUTH_RETRY_MS} on 401, exponential backoff on rate limit/server error, otherwise falls
	 * back to the last known good interval.
	 */
	private long delayForApiError(CloudApiException ex)
	{
		if (ex.isUnauthorized())
		{
			try
			{
				session.ensureSession();
			}
			catch (Exception ignored)
			{
			}
			return AUTH_RETRY_MS;
		}
		if (ex.isRateLimited() || ex.isServerError())
		{
			return nextBackoffDelayMs();
		}
		return Math.max(DEFAULT_POLL_MS, lastGoodPollAfterMs.get());
	}

	/** Doubles the current backoff (starting from {@link #DEFAULT_POLL_MS}), capped at {@link #BACKOFF_MAX_MS}. */
	private long nextBackoffDelayMs()
	{
		long next = backoffMs.get();
		if (next <= 0L)
		{
			next = DEFAULT_POLL_MS;
		}
		else
		{
			next = Math.min(BACKOFF_MAX_MS, next * 2L);
		}
		backoffMs.set(next);
		return Math.max(DEFAULT_POLL_MS, next);
	}
}

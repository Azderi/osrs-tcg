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

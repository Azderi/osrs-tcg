package com.osrstcg.cloud.session;

import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.Text;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.cloud.attest.CreditAttestCoalescer;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.trade.TradeCloudService;
import javax.inject.Provider;

/**
 * Settles offline hiscores gains into cloud credits once per login, and snapshots hiscores on
 * logout so offline progress since the snapshot can be settled next login. Optionally folds
 * spilled/pending attest events into the settle request. Handles transient
 * {@code hiscores_unavailable} failures with a single delayed retry. Blocking: methods issue
 * synchronous HTTP calls via {@link CloudApiClient} and must not run on the client/EDT thread,
 * except the scheduled retry body which runs on {@link #scheduler}.
 */
@Slf4j
final class HiscoresSettleService
{
	private static final long HISCORES_RETRY_DELAY_SEC = 30L;

	private String lastDisplayName;

	private final Client client;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final RestrictedWorldGuard restrictedWorldGuard;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final Provider<TradeCloudService> tradeCloudProvider;
	private final Provider<CreditAttestQueue> attestQueueProvider;
	private final Consumer<JsonObject> applySidebarStats;
	private final AtomicBoolean hiscoresSettledThisLogin;
	private final AtomicBoolean hiscoresRetryScheduled;
	private final java.util.function.BooleanSupplier needsCloudConsent;
	private final java.util.function.BooleanSupplier isAccountLocked;

	/** Wires collaborators and the shared login/retry flags owned by {@link CloudSessionService}. */
	HiscoresSettleService(
		Client client,
		CloudApiClient api,
		CloudTokenStore tokens,
		RestrictedWorldGuard restrictedWorldGuard,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		Provider<TradeCloudService> tradeCloudProvider,
		Provider<CreditAttestQueue> attestQueueProvider,
		Consumer<JsonObject> applySidebarStats,
		AtomicBoolean hiscoresSettledThisLogin,
		AtomicBoolean hiscoresRetryScheduled,
		java.util.function.BooleanSupplier needsCloudConsent,
		java.util.function.BooleanSupplier isAccountLocked)
	{
		this.client = client;
		this.api = api;
		this.tokens = tokens;
		this.restrictedWorldGuard = restrictedWorldGuard;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.tradeCloudProvider = tradeCloudProvider;
		this.attestQueueProvider = attestQueueProvider;
		this.applySidebarStats = applySidebarStats;
		this.hiscoresSettledThisLogin = hiscoresSettledThisLogin;
		this.hiscoresRetryScheduled = hiscoresRetryScheduled;
		this.needsCloudConsent = needsCloudConsent;
		this.isAccountLocked = isAccountLocked;
	}

	/**
	 * Records a hiscores snapshot at logout so future gains can be settled next login. No-op if the
	 * account is locked, consent is pending, no access token, the world is restricted, or the account
	 * hash/display name aren't known. Failures are logged at debug level and swallowed.
	 */
	void snapshotOnLogout()
	{
		if (isAccountLocked.getAsBoolean()
			|| tokens.getAccessToken() == null || needsCloudConsent.getAsBoolean())
		{
			return;
		}
		if (restrictedWorldGuard != null && restrictedWorldGuard.isRestricted())
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			return;
		}
		String displayName = resolveDisplayName();
		if (displayName == null)
		{
			return;
		}

		try
		{
			JsonObject response = api.settleHiscores(displayName, accountHash, true);
			if (response != null && response.has("skipped") && !response.get("skipped").isJsonNull()
				&& response.get("skipped").getAsBoolean())
			{
				log.debug("Hiscores logout snapshot skipped: {}",
					response.has("reason") ? response.get("reason").getAsString() : "skipped");
				return;
			}
			log.debug("Hiscores logout snapshot stored");
		}
		catch (CloudApiException ex)
		{
			log.debug("Hiscores logout snapshot failed: {} {}", ex.getCode(), ex.getMessage());
		}
		catch (Exception ex)
		{
			log.debug("Hiscores logout snapshot failed", ex);
		}
	}

	/**
	 * Settles offline hiscores gains since the last snapshot into credits, once per login (guarded by
	 * {@link #hiscoresSettledThisLogin}). Always calls settle-hiscores even when there is no spill —
	 * cached events are optional. On successful send with a non-empty snapshot, clears durable
	 * pending/spill. On error, delegates to {@link #handleSettleError}.
	 */
	void settleAfterCloudLogin()
	{
		if (hiscoresSettledThisLogin.get())
		{
			return;
		}
		if (tokens.getAccessToken() == null || needsCloudConsent.getAsBoolean() || isAccountLocked.getAsBoolean())
		{
			return;
		}
		if (restrictedWorldGuard != null && restrictedWorldGuard.isRestricted())
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			return;
		}
		String displayName = resolveDisplayName();
		if (displayName == null)
		{
			log.debug("Hiscores settle skipped: local player name not ready");
			return;
		}

		LoginCachePayload cache = prepareLoginCache();
		try
		{
			JsonObject response = api.settleHiscores(
				displayName,
				accountHash,
				cache.wireEvents,
				cache.overflowCredits,
				cache.overflowEventCount);
			// Successful HTTP delivery: clear durable cache for the attached snapshot (may be empty).
			if (cache.hadSnapshot)
			{
				attestQueueProvider.get().clearAfterSuccessfulSettleSend(cache.optimisticTotal);
			}
			hiscoresSettledThisLogin.set(true);
			applySettleResponse(response);
		}
		catch (CloudApiException ex)
		{
			handleSettleError(ex, accountHash, displayName);
		}
		catch (Exception ex)
		{
			log.warn("Hiscores settle failed", ex);
		}
	}

	/** Resets the once-per-login settle gate and retry-scheduled flag (e.g. on logout or lock). */
	void clearGate()
	{
		hiscoresSettledThisLogin.set(false);
		hiscoresRetryScheduled.set(false);
	}

	/**
	 * Classifies a settle failure: "not found"/forbidden/locked codes are treated as terminal for
	 * this login (marks settled, no retry); {@code hiscores_unavailable}/503 schedules one retry;
	 * anything else is just logged. Durable cache is left intact on transport/server failure.
	 */
	private void handleSettleError(CloudApiException ex, long accountHash, String displayName)
	{
		String code = ex.getCode() == null ? "" : ex.getCode();
		int status = ex.getStatus();
		if ("hiscores_not_found".equals(code) || status == 404)
		{
			hiscoresSettledThisLogin.set(true);
			log.info("Hiscores settle skipped: player not on hiscores ({})", ex.getMessage());
			return;
		}
		if ("sandbox_forbidden".equals(code)
			|| "quarantined".equals(code)
			|| "banned".equals(code)
			|| "account_banned".equals(code)
			|| "not_trade_eligible".equals(code)
			|| status == 403)
		{
			hiscoresSettledThisLogin.set(true);
			log.info("Hiscores settle forbidden ({}): {}", code, ex.getMessage());
			return;
		}
		if ("hiscores_unavailable".equals(code) || status == 503)
		{
			log.warn("Hiscores settle unavailable; scheduling one retry: {}", ex.getMessage());
			scheduleRetry(accountHash, displayName);
			return;
		}
		log.warn("Hiscores settle failed: {} {}", code, ex.getMessage());
	}

	/**
	 * Schedules a single delayed settle retry (guarded by {@link #hiscoresRetryScheduled} so only one
	 * retry is ever pending). The retry re-checks preconditions (not already settled, has token,
	 * consent granted, still the same account) before calling settle again — still always settles
	 * even with an empty cache.
	 */
	private void scheduleRetry(long accountHash, String displayName)
	{
		if (!hiscoresRetryScheduled.compareAndSet(false, true))
		{
			return;
		}
		scheduler.schedule(() ->
		{
			try
			{
				if (hiscoresSettledThisLogin.get()
					|| tokens.getAccessToken() == null
					|| needsCloudConsent.getAsBoolean()
					|| client.getAccountHash() != accountHash)
				{
					return;
				}
				String retryName = resolveDisplayName();
				if (retryName == null)
				{
					retryName = displayName;
				}
				LoginCachePayload cache = prepareLoginCache();
				JsonObject response = api.settleHiscores(
					retryName,
					accountHash,
					cache.wireEvents,
					cache.overflowCredits,
					cache.overflowEventCount);
				if (cache.hadSnapshot)
				{
					attestQueueProvider.get().clearAfterSuccessfulSettleSend(cache.optimisticTotal);
				}
				hiscoresSettledThisLogin.set(true);
				applySettleResponse(response);
			}
			catch (CloudApiException ex)
			{
				hiscoresSettledThisLogin.set(true);
				log.warn("Hiscores settle retry failed: {} {}", ex.getCode(), ex.getMessage());
			}
			catch (Exception ex)
			{
				hiscoresSettledThisLogin.set(true);
				log.warn("Hiscores settle retry failed", ex);
			}
		}, HISCORES_RETRY_DELAY_SEC, TimeUnit.SECONDS);
	}

	/** Current local player's sanitized name, falling back to the last known name if unavailable. */
	private String resolveDisplayName()
	{
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			String name = Text.sanitize(client.getLocalPlayer().getName());
			if (name != null && !name.isEmpty())
			{
				lastDisplayName = name;
				return name;
			}
		}
		return lastDisplayName;
	}

	/**
	 * Snapshot spill/pending, coalesce, and cap at {@link CreditAttestCoalescer#MAX_LOGIN_CACHE_CREDITS}.
	 * Empty spill still produces a valid payload so settle always runs.
	 */
	private LoginCachePayload prepareLoginCache()
	{
		CreditAttestQueue queue = attestQueueProvider.get();
		List<JsonObject> snapshot = queue.snapshotPendingForSettle();
		if (snapshot == null || snapshot.isEmpty())
		{
			return LoginCachePayload.empty();
		}
		List<JsonObject> coalesced = CreditAttestCoalescer.coalesce(snapshot);
		long optimisticTotal = 0L;
		for (JsonObject event : coalesced)
		{
			optimisticTotal += CreditAttestCoalescer.optimisticOf(event);
		}
		CreditAttestCoalescer.LoginCacheSplit split = CreditAttestCoalescer.takePriorityUntilOptimistic(
			coalesced, CreditAttestCoalescer.MAX_LOGIN_CACHE_CREDITS);
		List<JsonObject> wire = new ArrayList<>(split.payable.size());
		for (JsonObject event : split.payable)
		{
			wire.add(CreditAttestCoalescer.forWire(event));
		}
		return new LoginCachePayload(
			true,
			wire,
			split.overflowCredits,
			split.overflowEventCount,
			optimisticTotal);
	}

	/**
	 * Applies a settle response's sidebar stats and revision, and posts a chat toast when credits
	 * were accepted (hiscores and/or folded login-cache events). No-op if the response is null or
	 * marked skipped/throttled with no event credits.
	 */
	private void applySettleResponse(JsonObject response)
	{
		if (response == null)
		{
			return;
		}

		boolean skipped = response.has("skipped") && !response.get("skipped").isJsonNull()
			&& response.get("skipped").getAsBoolean();
		boolean eventsApplied = response.has("eventsApplied") && !response.get("eventsApplied").isJsonNull()
			&& response.get("eventsApplied").getAsBoolean();

		if (skipped && !eventsApplied)
		{
			log.debug("Hiscores settle throttled/skipped: {}",
				response.has("reason") ? response.get("reason").getAsString() : "settle_throttle");
			return;
		}

		applySidebarStats.accept(response);
		if (response.has("revision") && !response.get("revision").isJsonNull())
		{
			tradeCloudProvider.get().noteRevision(response.get("revision").getAsLong());
		}

		long accepted = 0L;
		if (response.has("accepted") && !response.get("accepted").isJsonNull()
			&& response.get("accepted").isJsonPrimitive())
		{
			accepted = response.get("accepted").getAsLong();
		}
		long eventsCredits = JsonObjects.readLong(response, "eventsCredits");
		long toastCredits = accepted + Math.max(0L, eventsCredits);
		if (toastCredits > 0L && !skipped)
		{
			String toast = buildToast(accepted, response);
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, toast);
		}
		else if (eventsCredits > 0L)
		{
			String toast = "Offline settle: +" + NumberFormatting.format(eventsCredits)
				+ " credits (cached events)";
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, toast);
		}
	}

	/** Formats the "Offline settle: +N credits (XP x, levels y, activities z)." chat toast text. */
	private static String buildToast(long accepted, JsonObject response)
	{
		StringBuilder sb = new StringBuilder("Offline settle: +");
		sb.append(NumberFormatting.format(accepted)).append(" credits");
		if (response.has("breakdown") && response.get("breakdown").isJsonObject())
		{
			JsonObject b = response.getAsJsonObject("breakdown");
			long xp = JsonObjects.readLong(b, "xpCredits");
			long levels = JsonObjects.readLong(b, "levelCredits");
			long activities = JsonObjects.readLong(b, "activityCredits");
			if (xp > 0L || levels > 0L || activities > 0L)
			{
				sb.append(" (");
				boolean first = true;
				if (xp > 0L)
				{
					sb.append("XP ").append(NumberFormatting.format(xp));
					first = false;
				}
				if (levels > 0L)
				{
					if (!first)
					{
						sb.append(", ");
					}
					sb.append("levels ").append(NumberFormatting.format(levels));
					first = false;
				}
				if (activities > 0L)
				{
					if (!first)
					{
						sb.append(", ");
					}
					sb.append("activities ").append(NumberFormatting.format(activities));
				}
				sb.append(')');
			}
		}
		return sb.toString();
	}

	private static final class LoginCachePayload
	{
		final boolean hadSnapshot;
		final List<JsonObject> wireEvents;
		final long overflowCredits;
		final int overflowEventCount;
		final long optimisticTotal;

		LoginCachePayload(
			boolean hadSnapshot,
			List<JsonObject> wireEvents,
			long overflowCredits,
			int overflowEventCount,
			long optimisticTotal)
		{
			this.hadSnapshot = hadSnapshot;
			this.wireEvents = wireEvents;
			this.overflowCredits = overflowCredits;
			this.overflowEventCount = overflowEventCount;
			this.optimisticTotal = optimisticTotal;
		}

		static LoginCachePayload empty()
		{
			return new LoginCachePayload(false, null, 0L, 0, 0L);
		}
	}
}

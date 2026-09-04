package com.osrstcg.cloud.session;

import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import com.google.gson.JsonObject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.cloud.trade.TradeCloudService;
import javax.inject.Provider;
/**
 * Settles offline hiscores gains into cloud credits once per login. Never called on logout —
 * {@link #clearGate()} cancels any pending retry so a delayed settle cannot fire after disconnect.
 * Handles transient {@code hiscores_unavailable} failures with a single delayed retry. Blocking:
 * methods issue synchronous HTTP calls via {@link CloudApiClient} and must not run on the
 * client/EDT thread, except the scheduled retry body which runs on {@link #scheduler}.
 */
@Slf4j
final class HiscoresSettleService
{
	private static final long HISCORES_RETRY_DELAY_SEC = 30L;
/** Longer than default {@code HISCORES_SETTLE_MIN_INTERVAL_MS} (60s) so throttle can clear. */
	private static final long SETTLE_THROTTLE_RETRY_DELAY_SEC = 70L;

	private final CachedDisplayName cachedDisplayName = new CachedDisplayName();

	private final Client client;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final RestrictedWorldGuard restrictedWorldGuard;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final Provider<TradeCloudService> tradeCloudProvider;
	private final Consumer<JsonObject> applySidebarStats;
	private final AtomicBoolean hiscoresSettledThisLogin;
	private final AtomicBoolean hiscoresRetryScheduled;
	private final BooleanSupplier needsCloudConsent;
	private final BooleanSupplier isAccountLocked;
/** Bumped by {@link #clearGate()} so in-flight/scheduled retries become no-ops after logout. */
	private final AtomicLong settleEpoch = new AtomicLong(0L);
	private final Object retryLock = new Object();
	private ScheduledFuture<?> retryFuture;
/** Wires collaborators and the shared login/retry flags owned by {@link CloudSessionService}. */
	HiscoresSettleService(
		Client client,
		CloudApiClient api,
		CloudTokenStore tokens,
		RestrictedWorldGuard restrictedWorldGuard,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		Provider<TradeCloudService> tradeCloudProvider,
		Consumer<JsonObject> applySidebarStats,
		AtomicBoolean hiscoresSettledThisLogin,
		AtomicBoolean hiscoresRetryScheduled,
		BooleanSupplier needsCloudConsent,
		BooleanSupplier isAccountLocked)
	{
		this.client = client;
		this.api = api;
		this.tokens = tokens;
		this.restrictedWorldGuard = restrictedWorldGuard;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.tradeCloudProvider = tradeCloudProvider;
		this.applySidebarStats = applySidebarStats;
		this.hiscoresSettledThisLogin = hiscoresSettledThisLogin;
		this.hiscoresRetryScheduled = hiscoresRetryScheduled;
		this.needsCloudConsent = needsCloudConsent;
		this.isAccountLocked = isAccountLocked;
	}
/**
	 * Settles offline hiscores gains into credits, once per login (guarded by
	 * {@link #hiscoresSettledThisLogin}). No-op when not logged into RuneScape (never on logout).
	 * On error, delegates to {@link #handleSettleError}.
	 */
	void settleAfterCloudLogin()
	{
		long epoch = settleEpoch.get();
		if (hiscoresSettledThisLogin.get() || !canSettleNow())
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			return;
		}
		String displayName = cachedDisplayName.resolve(client);
		if (displayName == null)
		{
			log.debug("Hiscores settle skipped: local player name not ready");
			return;
		}

		try
		{
			if (!stillValid(epoch) || !canSettleNow())
			{
				return;
			}
			JsonObject response = api.settleHiscores(displayName, accountHash);
			if (!stillValid(epoch))
			{
				return;
			}
			handleSettleResponse(response, accountHash, displayName, false);
		}
		catch (CloudApiException ex)
		{
			if (!stillValid(epoch))
			{
				return;
			}
			handleSettleError(ex, accountHash, displayName);
		}
		catch (Exception ex)
		{
			log.warn("Hiscores settle failed", ex);
		}
	}
/**
	 * Invalidates settle for this session: bumps the epoch, cancels any pending retry, and resets
	 * once-per-login flags. Called on logout/disconnect/lock so settle cannot fire afterward.
	 */
	void clearGate()
	{
		settleEpoch.incrementAndGet();
		hiscoresSettledThisLogin.set(false);
		hiscoresRetryScheduled.set(false);
		cancelRetry();
	}
/** True only while logged into RuneScape with tokens/consent/world gates open. */
	private boolean canSettleNow()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		if (tokens.getAccessToken() == null || needsCloudConsent.getAsBoolean() || isAccountLocked.getAsBoolean())
		{
			return false;
		}
		return !restrictedWorldGuard.isRestricted();
	}

	private boolean stillValid(long epoch)
	{
		return settleEpoch.get() == epoch;
	}
/**
	 * Applies sidebar credits from a settle response. Soft-skips ({@code hiscores_stale},
	 * {@code settle_throttle}) refresh credits but do not consume the once-per-login gate — a single
	 * delayed retry is scheduled so Jagex lag / cooldown can clear. Terminal successes mark settled.
	 */
	private void handleSettleResponse(
		JsonObject response,
		long accountHash,
		String displayName,
		boolean isRetry)
	{
		applySettleResponse(response);
		if (response == null)
		{
			hiscoresSettledThisLogin.set(true);
			return;
		}

		boolean skipped = JsonObjects.readBoolean(response, "skipped");
		String reason = JsonObjects.text(response, "reason");
		if (reason == null)
		{
			reason = "";
		}

		if (!isRetry && skipped && ("hiscores_stale".equals(reason) || "settle_throttle".equals(reason)))
		{
			long delaySec = "settle_throttle".equals(reason)
				? SETTLE_THROTTLE_RETRY_DELAY_SEC
				: HISCORES_RETRY_DELAY_SEC;
			log.info("Hiscores settle soft-skip ({}); scheduling retry in {}s", reason, delaySec);
			scheduleRetry(accountHash, displayName, delaySec);
			return;
		}

		hiscoresSettledThisLogin.set(true);
	}
/**
	 * Classifies a settle failure: "not found"/forbidden/locked codes are treated as terminal for
	 * this login (marks settled, no retry); {@code hiscores_unavailable}/503 schedules one retry;
	 * anything else is just logged.
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
			scheduleRetry(accountHash, displayName, HISCORES_RETRY_DELAY_SEC);
			return;
		}
		log.warn("Hiscores settle failed: {} {}", code, ex.getMessage());
	}
/**
	 * Schedules a single delayed settle retry (guarded by {@link #hiscoresRetryScheduled} so only one
	 * retry is ever pending). The retry re-checks preconditions and the settle epoch before calling
	 * settle again; {@link #clearGate()} cancels it on logout.
	 */
	private void scheduleRetry(long accountHash, String displayName, long delaySec)
	{
		if (!hiscoresRetryScheduled.compareAndSet(false, true))
		{
			return;
		}
		long epoch = settleEpoch.get();
		ScheduledFuture<?> future = scheduler.schedule(() ->
		{
			try
			{
				if (!stillValid(epoch)
					|| hiscoresSettledThisLogin.get()
					|| !canSettleNow()
					|| client.getAccountHash() != accountHash)
				{
					return;
				}
				String retryName = cachedDisplayName.resolve(client);
				if (retryName == null)
				{
					retryName = displayName;
				}
				if (retryName == null || !stillValid(epoch) || !canSettleNow())
				{
					return;
				}
				JsonObject response = api.settleHiscores(retryName, accountHash);
				if (!stillValid(epoch))
				{
					return;
				}
				handleSettleResponse(response, accountHash, retryName, true);
			}
			catch (CloudApiException ex)
			{
				if (!stillValid(epoch))
				{
					return;
				}
				hiscoresSettledThisLogin.set(true);
				log.warn("Hiscores settle retry failed: {} {}", ex.getCode(), ex.getMessage());
			}
			catch (Exception ex)
			{
				if (!stillValid(epoch))
				{
					return;
				}
				hiscoresSettledThisLogin.set(true);
				log.warn("Hiscores settle retry failed", ex);
			}
			finally
			{
				synchronized (retryLock)
				{
					retryFuture = null;
				}
			}
		}, delaySec, TimeUnit.SECONDS);
		synchronized (retryLock)
		{
			retryFuture = future;
		}
	}

	private void cancelRetry()
	{
		ScheduledFuture<?> future;
		synchronized (retryLock)
		{
			future = retryFuture;
			retryFuture = null;
		}
		if (future != null)
		{
			future.cancel(false);
		}
	}
/**
	 * Applies a settle response's sidebar credits/revision. Posts a chat toast when hiscores credits
	 * were accepted.
	 */
	private void applySettleResponse(JsonObject response)
	{
		if (response == null)
		{
			return;
		}

		boolean skipped = JsonObjects.readBoolean(response, "skipped");
		boolean hasCredits = response.has("credits") && !response.get("credits").isJsonNull();

		if (skipped)
		{
			String reason = JsonObjects.text(response, "reason");
			if (reason == null)
			{
				reason = "settle_throttle";
			}
			if (!hasCredits)
			{
				log.debug("Hiscores settle throttled/skipped: {}", reason);
				return;
			}
			log.debug("Hiscores settle throttled/skipped (refreshing sidebar credits): {}", reason);
		}

		if (hasCredits || response.has("openedPacks") || response.has("totalCreditsGained"))
		{
			applySidebarStats.accept(response);
		}
		Double revision = JsonObjects.readNullableDouble(response, "revision");
		if (revision != null)
		{
			tradeCloudProvider.get().noteRevision(Math.round(revision));
		}

		long accepted = JsonObjects.readLong(response, "accepted", 0L);
		if (accepted > 0L)
		{
			String toast = "Automatically credited "
				+ NumberFormatting.format(accepted)
				+ " credits based on the hiscores!";
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, toast);
		}
	}
}

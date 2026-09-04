package com.osrstcg.cloud.session;

import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import com.google.gson.JsonObject;
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
import com.osrstcg.cloud.trade.TradeCloudService;
import javax.inject.Provider;

/**
 * Settles offline hiscores gains into cloud credits once per login. Handles transient
 * {@code hiscores_unavailable} failures with a single delayed retry. Blocking: methods issue
 * synchronous HTTP calls via {@link CloudApiClient} and must not run on the client/EDT thread,
 * except the scheduled retry body which runs on {@link #scheduler}.
 */
@Slf4j
final class HiscoresSettleService
{
	private static final long HISCORES_RETRY_DELAY_SEC = 30L;
	/** Longer than default {@code HISCORES_SETTLE_MIN_INTERVAL_MS} (60s) so throttle can clear. */
	private static final long SETTLE_THROTTLE_RETRY_DELAY_SEC = 70L;

	private String lastDisplayName;

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
		this.applySidebarStats = applySidebarStats;
		this.hiscoresSettledThisLogin = hiscoresSettledThisLogin;
		this.hiscoresRetryScheduled = hiscoresRetryScheduled;
		this.needsCloudConsent = needsCloudConsent;
		this.isAccountLocked = isAccountLocked;
	}

	/**
	 * Settles offline hiscores gains into credits, once per login (guarded by
	 * {@link #hiscoresSettledThisLogin}). On error, delegates to {@link #handleSettleError}.
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

		try
		{
			JsonObject response = api.settleHiscores(displayName, accountHash);
			handleSettleResponse(response, accountHash, displayName, false);
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

		boolean skipped = response.has("skipped") && !response.get("skipped").isJsonNull()
			&& response.get("skipped").getAsBoolean();
		String reason = response.has("reason") && !response.get("reason").isJsonNull()
			? response.get("reason").getAsString()
			: "";

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
	 * retry is ever pending). The retry re-checks preconditions before calling settle again.
	 */
	private void scheduleRetry(long accountHash, String displayName, long delaySec)
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
				JsonObject response = api.settleHiscores(retryName, accountHash);
				handleSettleResponse(response, accountHash, retryName, true);
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
		}, delaySec, TimeUnit.SECONDS);
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
	 * Applies a settle response's sidebar credits/revision. Posts a chat toast when hiscores credits
	 * were accepted.
	 */
	private void applySettleResponse(JsonObject response)
	{
		if (response == null)
		{
			return;
		}

		boolean skipped = response.has("skipped") && !response.get("skipped").isJsonNull()
			&& response.get("skipped").getAsBoolean();
		boolean hasCredits = response.has("credits") && !response.get("credits").isJsonNull();

		if (skipped && !hasCredits)
		{
			log.debug("Hiscores settle throttled/skipped: {}",
				response.has("reason") ? response.get("reason").getAsString() : "settle_throttle");
			return;
		}

		if (skipped)
		{
			log.debug("Hiscores settle throttled/skipped (refreshing sidebar credits): {}",
				response.has("reason") ? response.get("reason").getAsString() : "settle_throttle");
		}

		if (hasCredits || response.has("openedPacks") || response.has("totalCreditsGained"))
		{
			applySidebarStats.accept(response);
		}
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
		if (accepted > 0L)
		{
			String toast = "You have been automatically credited "
				+ NumberFormatting.format(accepted)
				+ " credits based on hiscores!";
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, toast);
		}
	}
}

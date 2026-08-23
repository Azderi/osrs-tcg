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

/** Logout snapshot + login settle / retry / toast for Jagex hiscores credits. */
@Slf4j
final class HiscoresSettleService
{
	private static final long HISCORES_SETTLE_RETRY_DELAY_SEC = 30L;

	/** Last sanitized RSN when local player is cleared (e.g. logout snapshot). */
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
	private final AtomicBoolean hiscoresSettleRetryScheduled;
	private final java.util.function.BooleanSupplier needsCloudConsent;
	private final java.util.function.BooleanSupplier isAccountLocked;
	private final java.util.function.BooleanSupplier isDebugModePaused;

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
		AtomicBoolean hiscoresSettleRetryScheduled,
		java.util.function.BooleanSupplier needsCloudConsent,
		java.util.function.BooleanSupplier isAccountLocked,
		java.util.function.BooleanSupplier isDebugModePaused)
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
		this.hiscoresSettleRetryScheduled = hiscoresSettleRetryScheduled;
		this.needsCloudConsent = needsCloudConsent;
		this.isAccountLocked = isAccountLocked;
		this.isDebugModePaused = isDebugModePaused;
	}

	void snapshotOnLogout()
	{
		if (isDebugModePaused.getAsBoolean() || isAccountLocked.getAsBoolean()
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

	void clearGate()
	{
		hiscoresSettledThisLogin.set(false);
		hiscoresSettleRetryScheduled.set(false);
	}

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

	private void scheduleRetry(long accountHash, String displayName)
	{
		if (!hiscoresSettleRetryScheduled.compareAndSet(false, true))
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
		}, HISCORES_SETTLE_RETRY_DELAY_SEC, TimeUnit.SECONDS);
	}

	/** @return sanitized local player name, or last cached name if the client already cleared it */
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

	private void applySettleResponse(JsonObject response)
	{
		if (response == null)
		{
			return;
		}
		if (response.has("skipped") && !response.get("skipped").isJsonNull()
			&& response.get("skipped").getAsBoolean())
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
		if (response.has("accepted") && !response.get("accepted").isJsonNull())
		{
			accepted = response.get("accepted").getAsLong();
		}
		if (accepted > 0L)
		{
			String toast = buildToast(accepted, response);
			TcgPluginGameMessages.queueGameMessage(chatMessageManager, toast);
		}
	}

	private static String buildToast(long accepted, JsonObject response)
	{
		StringBuilder sb = new StringBuilder("[OSRS TCG] Offline settle: +");
		sb.append(NumberFormatting.format(accepted)).append(" credits");
		if (response.has("breakdown") && response.get("breakdown").isJsonObject())
		{
			JsonObject b = response.getAsJsonObject("breakdown");
			long xp = readLong(b, "xpCredits");
			long levels = readLong(b, "levelCredits");
			long activities = readLong(b, "activityCredits");
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
		sb.append('.');
		return sb.toString();
	}

	private static long readLong(JsonObject obj, String key)
	{
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull())
		{
			return 0L;
		}
		try
		{
			return obj.get(key).getAsLong();
		}
		catch (Exception ignored)
		{
			return 0L;
		}
	}
}

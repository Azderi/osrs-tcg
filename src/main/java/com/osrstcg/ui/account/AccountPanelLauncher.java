package com.osrstcg.ui.account;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.util.TcgPluginGameMessages;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.LinkBrowser;

@Slf4j
public final class AccountPanelLauncher
{
	private final CloudSessionService cloudSessionService;
	private final CloudApiClient cloudApiClient;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final Runnable updateButtonState;
	private final AtomicBoolean inFlight = new AtomicBoolean(false);

	public AccountPanelLauncher(
		CloudSessionService cloudSessionService,
		CloudApiClient cloudApiClient,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		Runnable updateButtonState)
	{
		this.cloudSessionService = cloudSessionService;
		this.cloudApiClient = cloudApiClient;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.updateButtonState = updateButtonState;
	}

	public AtomicBoolean inFlight()
	{
		return inFlight;
	}

	public void open()
	{
		open("/me");
	}

	public void open(String next)
	{
		if (cloudSessionService.isRestrictedWorld()
			|| !cloudSessionService.canOpenAccountPanel())
		{
			updateButtonState.run();
			return;
		}
		if (!inFlight.compareAndSet(false, true))
		{
			return;
		}
		updateButtonState.run();
		String nextPath = next == null || next.isBlank() ? "/me" : next.trim();
		scheduler.execute(() ->
		{
			try
			{
				JsonObject response = cloudApiClient.webCode(nextPath);
				String url = resolveWebLoginUrlOrFallback(response, nextPath);
				if (url == null || url.isEmpty())
				{
					throw new IllegalStateException("missing_login_url");
				}
				SwingUtilities.invokeLater(() -> LinkBrowser.browse(url));
			}
			catch (CloudApiException ex)
			{
				log.warn("Open account panel web-code failed: {} {}", ex.getCode(), ex.getMessage());
				queueOpenAccountPanelError(ex.getMessage());
			}
			catch (Exception ex)
			{
				log.warn("Open account panel failed", ex);
				queueOpenAccountPanelError(null);
			}
			finally
			{
				inFlight.set(false);
				SwingUtilities.invokeLater(updateButtonState);
			}
		});
	}

	public void updateManageAccountButtonState(JButton openAccountPanelButton, JButton openTradesButton)
	{
		if (openAccountPanelButton == null)
		{
			return;
		}
		boolean canOpen = cloudSessionService.canOpenAccountPanel();
		boolean busy = inFlight.get();
		openAccountPanelButton.setEnabled(canOpen && !busy);
		openAccountPanelButton.setToolTipText(canOpen
			? "Open the website signed in to your cloud account"
			: "Connect to cloud first");
		if (openTradesButton != null)
		{
			boolean tradesOk = canOpen && !cloudSessionService.isAccountLocked() && !busy;
			openTradesButton.setEnabled(tradesOk);
			openTradesButton.setToolTipText(tradesOk
				? "Open pending trades on the website"
				: cloudSessionService.isAccountLocked()
					? (cloudSessionService.isAccountBanned()
						? CloudSessionService.ACCOUNT_BANNED_STATUS
						: CloudSessionService.ACCOUNT_QUARANTINED_STATUS)
					: "Connect to cloud first");
		}
	}

	private void queueOpenAccountPanelError(String detail)
	{
		String message = detail == null || detail.isBlank()
			? "[OSRS TCG] Could not open account page"
			: "[OSRS TCG] Could not open account page - " + detail.trim();
		TcgPluginGameMessages.queueGameMessage(chatMessageManager, message);
	}

	public static String resolveWebLoginUrl(JsonObject response)
	{
		if (response != null && response.has("url") && !response.get("url").isJsonNull())
		{
			String url = response.get("url").getAsString();
			if (url != null && !url.isBlank())
			{
				return url.trim();
			}
		}
		return null;
	}

	public static String buildWebLoginUrl(String webBaseUrl, String code, String next)
	{
		if (code == null || code.isBlank())
		{
			return null;
		}
		String root = CloudEndpoints.trimTrailingSlash(webBaseUrl);
		if (root.isEmpty())
		{
			return null;
		}
		String nextPath = next == null || next.isBlank() ? "/me" : next.trim();
		String encodedCode = URLEncoder.encode(code.trim(), StandardCharsets.UTF_8);
		String encodedNext = URLEncoder.encode(nextPath, StandardCharsets.UTF_8);
		return root + "/login?code=" + encodedCode + "&next=" + encodedNext;
	}

	private String fallbackWebLoginUrl(String code, String next)
	{
		return buildWebLoginUrl(CloudEndpoints.WEB_BASE_URL, code, next);
	}

	private String resolveWebLoginUrlOrFallback(JsonObject response, String next)
	{
		if (response != null && response.has("code") && !response.get("code").isJsonNull())
		{
			String fromCode = fallbackWebLoginUrl(response.get("code").getAsString(), next);
			if (fromCode != null && !fromCode.isEmpty())
			{
				return fromCode;
			}
		}
		String url = resolveWebLoginUrl(response);
		if (url != null)
		{
			return CloudEndpoints.rewriteToWebBase(url);
		}
		return null;
	}
}

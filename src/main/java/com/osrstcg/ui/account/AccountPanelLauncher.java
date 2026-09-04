package com.osrstcg.ui.account;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.cloud.api.JsonObjects;
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
/**
 * Opens the OSRS TCG website account page (signed in via a one-time web code) from the sidebar,
 * and drives the enabled/disabled state of the account/trades buttons. Network calls run on the
 * given scheduler; UI updates are marshalled back onto the Swing EDT.
 */
@Slf4j
public final class AccountPanelLauncher
{
	private final CloudSessionService cloudSessionService;
	private final CloudApiClient cloudApiClient;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final Runnable updateButtonState;
	private final AtomicBoolean inFlight = new AtomicBoolean(false);
/** @param updateButtonState invoked (on the EDT) whenever button-enabled state may have changed */
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
/**
	 * Requests a login web-code from the cloud API and opens the resulting URL in the system browser.
	 * No-ops if the world is restricted, the panel can't currently be opened, or a request is already
	 * in flight. Runs the network call on {@link #scheduler} and browser launch on the EDT.
	 *
	 * @param next path to redirect to after web login; defaults to {@code /me} if blank
	 */
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
/** Refreshes enabled state and tooltips of the account panel and trades buttons from current cloud session state. */
	public void updateManageAccountState(JButton openAccountPanelButton, JButton openTradesButton)
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
/** Queues a chat message reporting that opening the account panel failed, with optional detail. */
	private void queueOpenAccountPanelError(String detail)
	{
		String message = detail == null || detail.isBlank()
			? "Could not open account page"
			: "Could not open account page - " + detail.trim();
		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, message);
	}
/** @return the {@code url} field from a web-code response, or {@code null} if absent/blank. */
	public static String resolveWebLoginUrl(JsonObject response)
	{
		return JsonObjects.textTrimmed(response, "url");
	}
/** @return a {@code /login} URL for the web base with the code and redirect path encoded, or {@code null} if inputs are missing. */
	public static String buildWebLoginUrl(String webBaseUrl, String code, String next)
	{
		if (code == null || code.isBlank() || webBaseUrl == null || webBaseUrl.isBlank())
		{
			return null;
		}
		String nextPath = next == null || next.isBlank() ? "/me" : next.trim();
		String encodedCode = URLEncoder.encode(code.trim(), StandardCharsets.UTF_8);
		String encodedNext = URLEncoder.encode(nextPath, StandardCharsets.UTF_8);
		return webBaseUrl + "/login?code=" + encodedCode + "&next=" + encodedNext;
	}
/**
	 * Prefers building a login URL from the response's one-time {@code code}; falls back to the
	 * response's {@code url} rewritten onto the configured web base.
	 */
	private String resolveWebLoginUrlOrFallback(JsonObject response, String next)
	{
		String code = JsonObjects.textTrimmed(response, "code");
		if (code != null)
		{
			String fromCode = buildWebLoginUrl(CloudEndpoints.WEB_BASE_URL, code, next);
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

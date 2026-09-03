package com.osrstcg.notify;

import com.google.gson.Gson;
import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.party.TcgPullPartyMessage;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.party.PartyService;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.notify.PullNotifySupport.PackSummaryContent;

/**
 * Notifications that leave the client: party broadcasts of pulls, and Discord-style webhook posts
 * for individual pulls and end-of-pack summaries.
 */
@Slf4j
@Singleton
public class PullExternalNotificationService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Client client;
	private final OsrsTcgConfig config;
	private final PullNotifySupport pullNotifySupport;
	private final PartyService partyService;

	/** Wires the HTTP client, JSON codec, game client, config, pull-content builder, and party service. */
	@Inject
	PullExternalNotificationService(
		OkHttpClient okHttpClient,
		Gson gson,
		Client client,
		OsrsTcgConfig config,
		PullNotifySupport pullNotifySupport,
		PartyService partyService)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.client = client;
		this.config = config;
		this.pullNotifySupport = pullNotifySupport;
		this.partyService = partyService;
	}

	/** Broadcasts a single pull to the current party. No-op if party-announce is disabled or not in a party. */
	public void notifyParty(String card, boolean newForCollection, boolean foil)
	{
		if (!config.partyAnnouncePulls() || !partyService.isInParty())
		{
			return;
		}
		try
		{
			TcgPullPartyMessage message = new TcgPullPartyMessage();
			message.setCardName(card);
			message.setNewForCollection(newForCollection);
			message.setFoil(foil);
			partyService.send(message);
		}
		catch (Exception ex)
		{
			log.debug("Could not send party pull message", ex);
		}
	}

	/** Posts a single-card pull as a Discord-style embed to every configured webhook URL. No-op if none configured. */
	public void sendWebhook(
		String card, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId)
	{
		List<HttpUrl> webhookUrls = configuredWebhookUrls();
		if (webhookUrls.isEmpty())
		{
			return;
		}
		try
		{
			PullNotifySupport.PullCardContent content = pullNotifySupport.pullCardContent(
				card, newForCollection, foil, instanceId, resolvePlayerName());
			String payload = gson.toJson(buildPayload(
				content.description, pullNotifySupport.statsPlainLine(), tier, content.imageUrl, content.inspectUrl));
			dispatchWebhook(card, webhookUrls, payload);
		}
		catch (Exception ex)
		{
			log.warn("Pull webhook failed before send for '{}'", card, ex);
		}
	}

	/** Posts an end-of-pack summary (new cards / duplicates) as a Discord-style embed to every configured webhook URL. */
	public void sendPackSummary(PackSummaryContent content)
	{
		List<HttpUrl> webhookUrls = configuredWebhookUrls();
		if (webhookUrls.isEmpty())
		{
			return;
		}
		try
		{
			String payload = gson.toJson(buildPayload(
				content.messageFor(resolvePlayerName()),
				pullNotifySupport.statsPlainLine(),
				content.tier,
				content.imageUrl,
				""));
			dispatchWebhook("pack summary", webhookUrls, payload);
		}
		catch (Exception ex)
		{
			log.warn("Pull webhook pack summary failed before send", ex);
		}
	}

	/** Parses the configured webhook URL(s) from config; logs a warning and returns empty if none parse. */
	private List<HttpUrl> configuredWebhookUrls()
	{
		String webhookUrl = config.pullWebhookUrl();
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			return List.of();
		}
		List<HttpUrl> webhookUrls = parseWebhookUrls(webhookUrl);
		if (webhookUrls.isEmpty())
		{
			log.warn("Pull webhook skipped: no valid URLs in config");
		}
		return webhookUrls;
	}

	/** Fires the same payload at each webhook URL independently. */
	private void dispatchWebhook(String card, List<HttpUrl> webhookUrls, String payload)
	{
		for (HttpUrl parsedUrl : webhookUrls)
		{
			enqueueWebhook(card, parsedUrl, payload);
		}
	}

	/** Sends one async POST to a webhook URL; logs the outcome (success, HTTP error, or transport failure). */
	private void enqueueWebhook(String card, HttpUrl parsedUrl, String payload)
	{
		Request request = new Request.Builder()
			.url(parsedUrl)
			.post(RequestBody.create(JSON, payload))
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			/** Logs a transport-level failure (connection/timeout, not an HTTP error status). */
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Pull webhook request failed for '{}' ({}): {}", card, maskWebhookUrl(parsedUrl), e.toString());
			}

			/** Logs success at debug level, or the response body (truncated) at warn level on a non-2xx status. */
			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (response.isSuccessful())
					{
						log.debug("Pull webhook sent for '{}' to {} (HTTP {})",
							card, maskWebhookUrl(parsedUrl), response.code());
						return;
					}
					String responseBody = body == null ? "" : body.string();
					log.warn(
						"Pull webhook rejected for '{}' at {} (HTTP {}): {}",
						card,
						maskWebhookUrl(parsedUrl),
						response.code(),
						truncateForLog(responseBody));
				}
				catch (IOException ex)
				{
					log.warn("Pull webhook response read failed for '{}' ({}): {}",
						card, maskWebhookUrl(parsedUrl), ex.toString());
				}
			}
		});
	}

	/** Splits the (possibly multi-line) config value into valid {@link HttpUrl}s, skipping blank/unparseable lines. */
	private static List<HttpUrl> parseWebhookUrls(String raw)
	{
		List<HttpUrl> urls = new ArrayList<>();
		for (String line : raw.split("\\R"))
		{
			String trimmed = line.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			HttpUrl parsed = HttpUrl.parse(trimmed);
			if (parsed == null)
			{
				log.warn("Pull webhook skipped invalid URL line: {}", maskWebhookUrl(trimmed));
				continue;
			}
			urls.add(parsed);
		}
		return urls;
	}

	/** Reduces a webhook URL to scheme/host/path for logging, redacting the last path segment (token) and query. */
	private static String maskWebhookUrl(Object url)
	{
		if (url == null)
		{
			return "<invalid>";
		}
		if (url instanceof HttpUrl)
		{
			HttpUrl parsed = (HttpUrl) url;
			StringBuilder path = new StringBuilder();
			int segments = parsed.pathSize();
			for (int i = 0; i < segments; i++)
			{
				path.append('/');
				path.append(i == segments - 1 ? "***" : parsed.encodedPathSegments().get(i));
			}
			return parsed.scheme() + "://" + parsed.host() + path;
		}
		String raw = url.toString().trim();
		if (raw.isEmpty())
		{
			return "<empty>";
		}
		HttpUrl parsed = HttpUrl.parse(raw);
		return parsed == null ? "<invalid>" : maskWebhookUrl(parsed);
	}

	/** Builds a single-embed Discord webhook payload from the description, footer, tier color, and links. */
	private static Map<String, Object> buildPayload(
		String description, String footerText, RarityMath.Tier tier, String imageUrl, String inspectUrl)
	{
		Map<String, Object> embed = new LinkedHashMap<>();
		embed.put("title", PullNotificationMessages.PLUGIN_TITLE);
		if (inspectUrl != null && !inspectUrl.isEmpty())
		{
			embed.put("url", inspectUrl);
		}
		embed.put("description", description);
		embed.put("color", discordColor(tier));
		if (footerText != null && !footerText.isEmpty())
		{
			embed.put("footer", Map.of("text", footerText));
		}
		if (imageUrl != null && !imageUrl.isEmpty())
		{
			embed.put("image", Map.of("url", imageUrl));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("embeds", List.of(embed));
		return payload;
	}

	/** Converts a rarity tier's {@link Color} to a Discord embed color integer; white if the tier is unknown. */
	private static int discordColor(RarityMath.Tier tier)
	{
		Color color = tier == null ? Color.WHITE : tier.getColor();
		return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
	}

	/** Sanitizes and returns the local player's name for webhook attribution, or a fallback label if unknown. */
	private String resolvePlayerName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return PullNotificationMessages.playerLabel(null);
		}
		return PullNotificationMessages.playerLabel(Text.sanitize(client.getLocalPlayer().getName()));
	}

	/** Collapses newlines and caps a webhook response body at 300 chars for logging. */
	private static String truncateForLog(String value)
	{
		if (value == null || value.isEmpty())
		{
			return "<empty body>";
		}
		String normalized = value.replace('\n', ' ').trim();
		return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
	}
}

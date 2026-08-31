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
import com.osrstcg.notify.PullNotificationMessages.PackPull;

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
			String imageUrl = pullNotifySupport.cardImageUrl(card);
			String inspectUrl = PullNotificationMessages.inspectUrl(instanceId);
			String description = PullNotificationMessages.collectionMessage(
				resolvePlayerName(), card, newForCollection, foil, inspectUrl);
			String statsLine = pullNotifySupport.statsPlainLine();
			String payload = gson.toJson(buildPayload(description, statsLine, tier, imageUrl, inspectUrl));
			dispatchWebhook(card, webhookUrls, payload);
		}
		catch (Exception ex)
		{
			log.warn("Pull webhook failed before send for '{}'", card, ex);
		}
	}

	public void sendPackSummary(List<PackPull> pulls)
	{
		List<HttpUrl> webhookUrls = configuredWebhookUrls();
		if (webhookUrls.isEmpty())
		{
			return;
		}
		pullNotifySupport.packSummaryContent(pulls, resolvePlayerName()).ifPresent(content ->
		{
			try
			{
				String payload = gson.toJson(buildPayload(
					content.summaryMessage,
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
		});
	}

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

	private void dispatchWebhook(String card, List<HttpUrl> webhookUrls, String payload)
	{
		for (HttpUrl parsedUrl : webhookUrls)
		{
			enqueueWebhook(card, parsedUrl, payload);
		}
	}

	private void enqueueWebhook(String card, HttpUrl parsedUrl, String payload)
	{
		Request request = new Request.Builder()
			.url(parsedUrl)
			.post(RequestBody.create(JSON, payload))
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Pull webhook request failed for '{}' ({}): {}", card, maskWebhookUrl(parsedUrl), e.toString());
			}

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

	private static String maskWebhookUrl(Object url)
	{
		if (url == null)
		{
			return "<invalid>";
		}
		if (url instanceof HttpUrl)
		{
			HttpUrl parsed = (HttpUrl) url;
			return parsed.scheme() + "://" + parsed.host() + parsed.encodedPath();
		}
		String raw = url.toString().trim();
		if (raw.isEmpty())
		{
			return "<empty>";
		}
		HttpUrl parsed = HttpUrl.parse(raw);
		return parsed == null ? "<invalid>" : maskWebhookUrl(parsed);
	}

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
		List<Map<String, Object>> embeds = new ArrayList<>();
		embeds.add(embed);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("embeds", embeds);
		return payload;
	}

	private static int discordColor(RarityMath.Tier tier)
	{
		Color color = tier == null ? Color.WHITE : tier.getColor();
		return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
	}

	private String resolvePlayerName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return "Unknown player";
		}
		return Text.sanitize(client.getLocalPlayer().getName());
	}

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

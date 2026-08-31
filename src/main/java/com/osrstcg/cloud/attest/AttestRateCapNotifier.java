package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;

@Slf4j
@Singleton
public final class AttestRateCapNotifier
{
	static final long RATE_CAP_THROTTLE_MS = 3L * 60_000L;

	private static final String RATE_CAP_PREFIX = "rate_cap";

	private final Consumer<String> chatSink;
	private final AtomicLong lastRateCapWarnAtMs = new AtomicLong(0L);

	@Inject
	AttestRateCapNotifier(ChatMessageManager chatMessageManager)
	{
		this(body -> TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, body));
	}

	AttestRateCapNotifier(Consumer<String> chatSink)
	{
		this.chatSink = chatSink == null ? body -> { } : chatSink;
	}

	public void reset()
	{
		lastRateCapWarnAtMs.set(0L);
	}

	public void onAttestResponse(JsonObject response)
	{
		onAttestResponse(response, System.currentTimeMillis());
	}

	void onAttestResponse(JsonObject response, long nowMs)
	{
		if (response == null)
		{
			return;
		}

		boolean quarantined = response.has("quarantined")
			&& !response.get("quarantined").isJsonNull()
			&& response.get("quarantined").getAsBoolean();

		List<String> rateCapReasons = collectRateCapReasons(response);
		if (!rateCapReasons.isEmpty())
		{
			log.info("Credit attest rate cap: {}", rateCapReasons);
			maybeWarnRateCap(rateCapReasons, nowMs);
		}
		else
		{
			log.debug("Credit attest rejects (no rate_cap): {}", formatAllRejectReasons(response));
		}

		if (quarantined)
		{
			log.warn("Credit attest response quarantined=true");
		}
	}

	static List<String> collectRateCapReasons(JsonObject response)
	{
		Set<String> reasons = new LinkedHashSet<>();
		if (response == null || !response.has("rejected") || !response.get("rejected").isJsonArray())
		{
			return List.of();
		}
		JsonArray rejected = response.getAsJsonArray("rejected");
		for (JsonElement el : rejected)
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			JsonObject row = el.getAsJsonObject();
			if (!row.has("reason") || row.get("reason").isJsonNull())
			{
				continue;
			}
			String reason;
			try
			{
				reason = row.get("reason").getAsString();
			}
			catch (RuntimeException ex)
			{
				continue;
			}
			if (reason != null && isRateCapReason(reason))
			{
				reasons.add(reason.trim());
			}
		}
		return List.copyOf(reasons);
	}

	static boolean isRateCapReason(String reason)
	{
		if (reason == null || reason.isBlank())
		{
			return false;
		}
		return reason.trim().toLowerCase(Locale.ROOT).startsWith(RATE_CAP_PREFIX);
	}

	static String playerFacingRateCapMessage(List<String> reasons)
	{
		String specific = mapSpecificMessage(reasons);
		if (specific != null)
		{
			return specific;
		}
		return "Credit rate limit hit - some XP/kills were not credited this hour. Try again later.";
	}

	private static String mapSpecificMessage(List<String> reasons)
	{
		if (reasons == null || reasons.isEmpty())
		{
			return null;
		}
		boolean skill = false;
		boolean level = false;
		boolean kills = false;
		boolean activity = false;
		boolean global = false;
		for (String r : reasons)
		{
			String key = r.toLowerCase(Locale.ROOT);
			if (key.contains("skill"))
			{
				skill = true;
			}
			else if (key.contains("level_up"))
			{
				level = true;
			}
			else if (key.contains("kill"))
			{
				kills = true;
			}
			else if (key.contains("activity"))
			{
				activity = true;
			}
			else if (key.contains("global"))
			{
				global = true;
			}
		}
		int kinds = (skill ? 1 : 0) + (level ? 1 : 0) + (kills ? 1 : 0) + (activity ? 1 : 0) + (global ? 1 : 0);
		if (kinds != 1)
		{
			return null;
		}
		if (skill)
		{
			return "Credit rate limit hit: Some skill XP was not credited this hour. Try again later.";
		}
		if (level)
		{
			return "Credit rate limit hit: Some level-up credits were not applied. Try again later.";
		}
		if (kills)
		{
			return "Credit rate limit hit: Some NPC kills were not credited this hour. Try again later.";
		}
		if (activity)
		{
			return "Credit rate limit hit: Some activity credits were skipped. Try again later.";
		}
		if (global)
		{
			return "Credit rate limit hit: Hourly or daily credit cap reached. Try again later.";
		}
		return null;
	}

	private void maybeWarnRateCap(List<String> reasons, long nowMs)
	{
		while (true)
		{
			long last = lastRateCapWarnAtMs.get();
			if (last > 0L && nowMs - last < RATE_CAP_THROTTLE_MS)
			{
				log.debug("Rate-cap warning throttled (last={}ms ago)", nowMs - last);
				return;
			}
			if (lastRateCapWarnAtMs.compareAndSet(last, nowMs))
			{
				chatSink.accept(playerFacingRateCapMessage(reasons));
				return;
			}
		}
	}

	private static String formatAllRejectReasons(JsonObject response)
	{
		if (response == null || !response.has("rejected") || !response.get("rejected").isJsonArray())
		{
			return "[]";
		}
		List<String> out = new ArrayList<>();
		for (JsonElement el : response.getAsJsonArray("rejected"))
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			JsonObject row = el.getAsJsonObject();
			if (row.has("reason") && !row.get("reason").isJsonNull())
			{
				try
				{
					out.add(row.get("reason").getAsString());
				}
				catch (RuntimeException ignored)
				{
				}
			}
		}
		return out.toString();
	}
}

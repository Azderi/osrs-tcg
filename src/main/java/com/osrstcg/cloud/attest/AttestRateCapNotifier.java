package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
/**
 * Watches credit attest responses for {@code rateCapAfterMs} pauses and {@code rate_cap} rejection
 * reasons, posting a throttled player-facing chat warning. Safe to call from any thread; internal
 * state is a single atomic timestamp.
 */
@Slf4j
@Singleton
public final class AttestRateCapNotifier
{
	static final long RATE_CAP_THROTTLE_MS = 3L * 60_000L;
	static final long MINUTE_MS = 60_000L;

	private static final String RATE_CAP_PREFIX = "rate_cap";
	private static final String HOURLY_REJECT_MESSAGE =
		"Credit rate limit hit - some credits were not applied this hour. Try again later.";

	private final Consumer<String> chatSink;
	private final AtomicLong lastRateCapWarnAtMs = new AtomicLong(0L);
/** Injected constructor: routes warnings to the prefixed game chat. */
	@Inject
	AttestRateCapNotifier(ChatMessageManager chatMessageManager)
	{
		this(body -> TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, body));
	}
/** Test/internal constructor with a pluggable chat sink; a null sink is replaced with a no-op. */
	AttestRateCapNotifier(Consumer<String> chatSink)
	{
		this.chatSink = chatSink == null ? body -> { } : chatSink;
	}
/** Clears the throttle so the next rate-cap rejection warns immediately. Called on session reset. */
	public void reset()
	{
		lastRateCapWarnAtMs.set(0L);
	}
/** Inspects an attest response for rate-cap pauses/rejections and quarantine, using the current time. */
	public void onAttestResponse(JsonObject response)
	{
		onAttestResponse(response, System.currentTimeMillis());
	}
/**
	 * Logs rate-cap pause or rejection found in {@code response} and, subject to throttling,
	 * warns the player in chat. {@code rateCapAfterMs} takes precedence over reject-reason messages.
	 * Also logs a warning if the response is marked quarantined.
	 */
	void onAttestResponse(JsonObject response, long nowMs)
	{
		if (response == null)
		{
			return;
		}

		boolean quarantined = JsonObjects.readBoolean(response, "quarantined");

		long rateCapAfterMs = CreditAttestQueue.parseRateCapAfterMs(response);
		if (rateCapAfterMs > 0L)
		{
			log.info("Credit attest rate cap pause: {}ms", rateCapAfterMs);
			maybeWarnMessage(playerFacingRateCapPauseMessage(rateCapAfterMs), nowMs);
		}
		else if (hasRateCapReject(response))
		{
			log.info("Credit attest rate cap reject");
			maybeWarnMessage(HOURLY_REJECT_MESSAGE, nowMs);
		}
		else
		{
			log.debug("Credit attest rejects (no rate_cap): {}", CreditAttestQueue.formatRejectedReasons(response));
		}

		if (quarantined)
		{
			log.warn("Credit attest response quarantined=true");
		}
	}
/** True if {@code response.rejected} contains any reason starting with {@code rate_cap}. */
	static boolean hasRateCapReject(JsonObject response)
	{
		if (response == null || !response.has("rejected") || !response.get("rejected").isJsonArray())
		{
			return false;
		}
		JsonArray rejected = response.getAsJsonArray("rejected");
		for (JsonElement el : rejected)
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			String reason = JsonObjects.text(el.getAsJsonObject(), "reason");
			if (isRateCapReason(reason))
			{
				return true;
			}
		}
		return false;
	}
/** True if {@code reason}, case-insensitively, starts with the {@code rate_cap} prefix. */
	static boolean isRateCapReason(String reason)
	{
		if (reason == null || reason.isBlank())
		{
			return false;
		}
		return reason.trim().toLowerCase(Locale.ROOT).startsWith(RATE_CAP_PREFIX);
	}
/**
	 * Builds the pause chat message for a positive {@code rateCapAfterMs}:
	 * {@code Credit rate limit hit. Credit events paused for X minutes.}
	 */
	static String playerFacingRateCapPauseMessage(long rateCapAfterMs)
	{
		long minutes = rateCapPauseMinutes(rateCapAfterMs);
		return "Credit rate limit hit. Credit events paused for " + minutes + " minutes.";
	}
/** Ceil of {@code rateCapAfterMs} in whole minutes; at least 1 when {@code rateCapAfterMs > 0}. */
	static long rateCapPauseMinutes(long rateCapAfterMs)
	{
		if (rateCapAfterMs <= 0L)
		{
			return 0L;
		}
		return Math.max(1L, (rateCapAfterMs + MINUTE_MS - 1L) / MINUTE_MS);
	}
/** Posts {@code message} unless a rate-cap warning was already sent within {@link #RATE_CAP_THROTTLE_MS}. */
	private void maybeWarnMessage(String message, long nowMs)
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
				chatSink.accept(message);
				return;
			}
		}
	}
}

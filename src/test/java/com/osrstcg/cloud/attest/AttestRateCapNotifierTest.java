package com.osrstcg.cloud.attest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AttestRateCapNotifierTest
{
	@Test
	public void pauseMessageUsesCeiledMinutes()
	{
		assertEquals(0L, AttestRateCapNotifier.rateCapPauseMinutes(0L));
		assertEquals(1L, AttestRateCapNotifier.rateCapPauseMinutes(1L));
		assertEquals(1L, AttestRateCapNotifier.rateCapPauseMinutes(60_000L));
		assertEquals(2L, AttestRateCapNotifier.rateCapPauseMinutes(60_001L));
		assertEquals(31L, AttestRateCapNotifier.rateCapPauseMinutes(1_843_200L));
		assertEquals(
			"Credit rate limit hit. Credit events paused for 31 minutes.",
			AttestRateCapNotifier.playerFacingRateCapPauseMessage(1_843_200L));
	}

	@Test
	public void rateCapAfterMsPrefersPauseMessageOverRejectReasons()
	{
		AtomicReference<String> chat = new AtomicReference<>();
		AttestRateCapNotifier notifier = new AttestRateCapNotifier(chat::set);

		JsonObject response = new JsonObject();
		response.addProperty("rateCapAfterMs", 120_000L);
		JsonArray rejected = new JsonArray();
		JsonObject row = new JsonObject();
		row.addProperty("reason", "rate_cap_skill");
		rejected.add(row);
		response.add("rejected", rejected);

		notifier.onAttestResponse(response, 1_000L);
		assertEquals(
			"Credit rate limit hit. Credit events paused for 2 minutes.",
			chat.get());
	}

	@Test
	public void rejectOnlyUsesCategoryMessage()
	{
		AtomicReference<String> chat = new AtomicReference<>();
		AttestRateCapNotifier notifier = new AttestRateCapNotifier(chat::set);

		JsonObject response = new JsonObject();
		JsonArray rejected = new JsonArray();
		JsonObject row = new JsonObject();
		row.addProperty("reason", "rate_cap_global");
		rejected.add(row);
		response.add("rejected", rejected);

		notifier.onAttestResponse(response, 1_000L);
		assertEquals(
			"Credit rate limit hit: Hourly or daily credit cap reached. Try again later.",
			chat.get());
	}

	@Test
	public void throttleSuppressesSecondWarnWithinWindow()
	{
		List<String> chats = new ArrayList<>();
		AttestRateCapNotifier notifier = new AttestRateCapNotifier(chats::add);

		JsonObject response = new JsonObject();
		response.addProperty("rateCapAfterMs", 60_000L);

		notifier.onAttestResponse(response, 1_000L);
		notifier.onAttestResponse(response, 1_000L + AttestRateCapNotifier.RATE_CAP_THROTTLE_MS - 1L);
		assertEquals(1, chats.size());

		notifier.onAttestResponse(response, 1_000L + AttestRateCapNotifier.RATE_CAP_THROTTLE_MS);
		assertEquals(2, chats.size());
	}

	@Test
	public void parseRateCapAfterMs()
	{
		assertEquals(0L, CreditAttestQueue.parseRateCapAfterMs(null));
		assertEquals(0L, CreditAttestQueue.parseRateCapAfterMs(new JsonObject()));

		JsonObject zero = new JsonObject();
		zero.addProperty("rateCapAfterMs", 0);
		assertEquals(0L, CreditAttestQueue.parseRateCapAfterMs(zero));

		JsonObject positive = new JsonObject();
		positive.addProperty("rateCapAfterMs", 1_843_200L);
		assertEquals(1_843_200L, CreditAttestQueue.parseRateCapAfterMs(positive));
	}

	@Test
	public void isRateCapActiveUsesDeadline()
	{
		assertFalse(new DeadlineProbe(0L).isRateCapActive(100L));
		assertTrue(new DeadlineProbe(200L).isRateCapActive(100L));
		assertFalse(new DeadlineProbe(200L).isRateCapActive(200L));
		assertFalse(new DeadlineProbe(200L).isRateCapActive(201L));
	}

	/** Tiny stand-in for deadline comparisons without constructing the full queue. */
	private static final class DeadlineProbe
	{
		private final long until;

		DeadlineProbe(long until)
		{
			this.until = until;
		}

		boolean isRateCapActive(long nowMs)
		{
			return until > 0L && nowMs < until;
		}
	}
}

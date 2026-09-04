package com.osrstcg.cloud.attest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public class CreditAttestCoalescerTest
{
	private static final long HOUR = CreditAttestCoalescer.HOUR_MS;
	/** Fixed base inside an hour (not near a boundary). */
	private static final long T0 = 1_700_000_000_000L;

	@Test
	public void sameSkillSameHourMergesXp()
	{
		List<JsonObject> raw = List.of(
			xp("WOODCUTTING", 1000L, T0),
			xp("WOODCUTTING", 2500L, T0 + 60_000L)
		);
		List<JsonObject> out = CreditAttestCoalescer.coalesce(raw);
		assertEquals(1, out.size());
		JsonObject event = out.get(0);
		assertEquals(CreditAttestCoalescer.TYPE_XP_CHUNK, event.get("type").getAsString());
		assertEquals(3500L, event.getAsJsonObject("evidence").get("xpDelta").getAsLong());
		assertEquals(T0 + 60_000L, event.get("at").getAsLong());
	}

	@Test
	public void sameSkillAcrossHourBoundarySplitsXp()
	{
		long hourEnd = (T0 / HOUR) * HOUR + HOUR;
		List<JsonObject> raw = List.of(
			xp("WOODCUTTING", 1000L, hourEnd - 1L),
			xp("WOODCUTTING", 2000L, hourEnd),
			xp("WOODCUTTING", 3000L, hourEnd + 1L)
		);
		List<JsonObject> out = CreditAttestCoalescer.coalesce(raw);
		assertEquals(2, out.size());

		List<JsonObject> sorted = new ArrayList<>(out);
		sorted.sort(Comparator.comparingLong(e -> e.get("at").getAsLong()));

		assertEquals(1000L, sorted.get(0).getAsJsonObject("evidence").get("xpDelta").getAsLong());
		assertEquals(hourEnd - 1L, sorted.get(0).get("at").getAsLong());

		assertEquals(5000L, sorted.get(1).getAsJsonObject("evidence").get("xpDelta").getAsLong());
		assertEquals(hourEnd + 1L, sorted.get(1).get("at").getAsLong());
	}

	@Test
	public void combatSkillXpDropped()
	{
		List<JsonObject> raw = List.of(
			xp("ATTACK", 5000L, T0),
			xp("WOODCUTTING", 1000L, T0)
		);
		List<JsonObject> out = CreditAttestCoalescer.coalesce(raw);
		assertEquals(1, out.size());
		assertEquals("WOODCUTTING", out.get(0).getAsJsonObject("evidence").get("skill").getAsString());
	}

	@Test
	public void sameNpcSameHourStacksKills()
	{
		List<JsonObject> raw = List.of(
			kill(42, "Goblin", 2, 3, T0),
			kill(42, "Goblin", 2, 2, T0 + 30_000L)
		);
		List<JsonObject> out = CreditAttestCoalescer.coalesce(raw);
		assertEquals(1, out.size());
		assertEquals(5, out.get(0).getAsJsonObject("evidence").get("amount").getAsInt());
		assertEquals(T0 + 30_000L, out.get(0).get("at").getAsLong());
	}

	@Test
	public void sameNpcDifferentHoursSplitKills()
	{
		long hourEnd = (T0 / HOUR) * HOUR + HOUR;
		List<JsonObject> raw = List.of(
			kill(42, "Goblin", 2, 1, hourEnd - 1L),
			kill(42, "Goblin", 2, 1, hourEnd)
		);
		List<JsonObject> out = CreditAttestCoalescer.coalesce(raw);
		assertEquals(2, out.size());
		for (JsonObject event : out)
		{
			assertEquals(CreditAttestCoalescer.TYPE_NPC_KILL, event.get("type").getAsString());
			assertEquals(1, event.getAsJsonObject("evidence").get("amount").getAsInt());
		}
	}

	@Test
	public void epochHourMatchesHourMsDivision()
	{
		assertEquals(T0 / HOUR, CreditAttestCoalescer.epochHour(T0));
		assertEquals(0L, CreditAttestCoalescer.epochHour(-5L));
		assertTrue(CreditAttestCoalescer.epochHour(HOUR) > CreditAttestCoalescer.epochHour(HOUR - 1L));
	}

	private static JsonObject xp(String skill, long xpDelta, long at)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill);
		evidence.addProperty("xpDelta", xpDelta);
		JsonObject event = new JsonObject();
		event.addProperty("type", CreditAttestCoalescer.TYPE_XP_CHUNK);
		event.add("evidence", evidence);
		event.addProperty("at", at);
		return event;
	}

	private static JsonObject kill(int npcId, String npcName, int combatLevel, int amount, long at)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("npcId", npcId);
		evidence.addProperty("npcName", npcName);
		evidence.addProperty("combatLevel", combatLevel);
		evidence.addProperty("amount", amount);
		JsonObject event = new JsonObject();
		event.addProperty("type", CreditAttestCoalescer.TYPE_NPC_KILL);
		event.add("evidence", evidence);
		event.addProperty("at", at);
		return event;
	}
}

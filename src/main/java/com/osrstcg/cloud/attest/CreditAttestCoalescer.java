package com.osrstcg.cloud.attest;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static com.osrstcg.cloud.attest.CreditAttestQueue.evidenceInt;
import static com.osrstcg.cloud.attest.CreditAttestQueue.evidenceLong;
import static com.osrstcg.cloud.attest.CreditAttestQueue.evidenceObject;
import static com.osrstcg.cloud.attest.CreditAttestQueue.evidenceString;

public final class CreditAttestCoalescer
{
	public static final int MAX_BATCH = 100;
	public static final int MAX_KILL_AMOUNT = 500;
	public static final int EARLY_FLUSH_COALESCED = 80;
	public static final long HOUR_MS = 3_600_000L;

	public static final String TYPE_NPC_KILL = "npc_kill";
	public static final String TYPE_XP_CHUNK = "xp_chunk";
	public static final String TYPE_LEVEL_UP = "level_up";
	public static final String TYPE_ACTIVITY = "activity";

	public static final String CLIENT_OPTIMISTIC_CREDITS = "_optimisticCredits";

	private static final Set<String> COMBAT_SKILLS_BLOCK_XP = combatSkillSet(
		"ATTACK", "STRENGTH", "DEFENCE", "RANGED", "MAGIC");
	private static final String HITPOINTS_SKILL_KEY = "HITPOINTS";

	private CreditAttestCoalescer()
	{
	}

	public static List<JsonObject> coalesce(List<JsonObject> rawEvents)
	{
		if (rawEvents == null || rawEvents.isEmpty())
		{
			return List.of();
		}

		Map<String, XpAgg> xpBySkillHour = new LinkedHashMap<>();
		Map<String, LevelAgg> levelBySkill = new LinkedHashMap<>();
		Map<KillKey, KillAgg> kills = new LinkedHashMap<>();
		List<JsonObject> activities = new ArrayList<>();

		for (JsonObject raw : rawEvents)
		{
			if (raw == null)
			{
				continue;
			}
			String type = JsonObjects.text(raw, "type");
			if (type == null || type.isEmpty())
			{
				continue;
			}
			JsonObject evidence = evidenceObject(raw);
			long at = atOf(raw);
			long optimistic = optimisticOf(raw);

			switch (type)
			{
				case TYPE_XP_CHUNK:
					mergeXp(xpBySkillHour, evidence, at, optimistic);
					break;
				case TYPE_LEVEL_UP:
					mergeLevelUp(levelBySkill, evidence, at, optimistic);
					break;
				case TYPE_NPC_KILL:
					mergeKill(kills, evidence, at, optimistic);
					break;
				case TYPE_ACTIVITY:
					activities.add(copyEvent(TYPE_ACTIVITY, evidence, at, optimistic));
					break;
				default:
					activities.add(copyEvent(type, evidence, at, optimistic));
					break;
			}
		}

		List<JsonObject> out = new ArrayList<>();
		for (Map.Entry<String, LevelAgg> e : levelBySkill.entrySet())
		{
			LevelAgg agg = e.getValue();
			if (agg.toLevel > agg.fromLevel)
			{
				out.add(buildLevelUp(agg.emitSkill, agg.fromLevel, agg.toLevel, agg.lastAt, agg.optimisticCredits));
			}
		}
		for (Map.Entry<String, XpAgg> e : xpBySkillHour.entrySet())
		{
			XpAgg agg = e.getValue();
			if (agg.xpDelta > 0L)
			{
				out.add(buildXp(agg.emitSkill, agg.xpDelta, agg.lastAt, agg.optimisticCredits));
			}
		}
		for (Map.Entry<KillKey, KillAgg> e : kills.entrySet())
		{
			KillKey key = e.getKey();
			KillAgg agg = e.getValue();
			int remaining = agg.amount;
			long optimisticRemaining = agg.optimisticCredits;
			while (remaining > 0)
			{
				int chunk = Math.min(MAX_KILL_AMOUNT, remaining);
				long chunkOptimistic = remaining <= chunk
					? optimisticRemaining
					: (agg.amount <= 0 ? 0L : (agg.optimisticCredits * chunk) / agg.amount);
				chunkOptimistic = Math.min(chunkOptimistic, optimisticRemaining);
				out.add(buildKill(key.npcId, key.npcName, key.combatLevel, chunk, agg.lastAt, chunkOptimistic));
				optimisticRemaining -= chunkOptimistic;
				remaining -= chunk;
			}
		}
		out.addAll(activities);
		return out;
	}

	public static int estimateCoalescedCount(List<JsonObject> rawEvents)
	{
		return coalesce(rawEvents).size();
	}

	public static List<JsonObject> takePriorityBatch(List<JsonObject> coalesced, int max)
	{
		if (coalesced == null || coalesced.isEmpty() || max <= 0)
		{
			return List.of();
		}
		if (coalesced.size() <= max)
		{
			List<JsonObject> all = new ArrayList<>(coalesced);
			coalesced.clear();
			return all;
		}

		List<Scored> scored = new ArrayList<>(coalesced.size());
		for (int i = 0; i < coalesced.size(); i++)
		{
			scored.add(new Scored(i, coalesced.get(i), priorityScore(coalesced.get(i))));
		}
		scored.sort(Comparator
			.comparingLong((Scored s) -> s.score).reversed()
			.thenComparingInt(s -> s.index));

		Set<Integer> takeIdx = new HashSet<>();
		List<JsonObject> batch = new ArrayList<>(max);
		for (int i = 0; i < max && i < scored.size(); i++)
		{
			takeIdx.add(scored.get(i).index);
			batch.add(scored.get(i).event);
		}

		List<JsonObject> leftover = new ArrayList<>(coalesced.size() - batch.size());
		for (int i = 0; i < coalesced.size(); i++)
		{
			if (!takeIdx.contains(i))
			{
				leftover.add(coalesced.get(i));
			}
		}
		coalesced.clear();
		coalesced.addAll(leftover);
		return batch;
	}

	public static boolean isCombatSkillName(String skill)
	{
		return COMBAT_SKILLS_BLOCK_XP.contains(normalizeSkillKey(skill));
	}

	public static boolean isHitpointsSkillName(String skill)
	{
		String key = normalizeSkillKey(skill);
		return key.equals(HITPOINTS_SKILL_KEY) || key.startsWith(HITPOINTS_SKILL_KEY + " ");
	}

	public static boolean isHitpointsXpOnly(List<JsonObject> events)
	{
		if (events == null || events.isEmpty())
		{
			return false;
		}
		for (JsonObject event : events)
		{
			if (event == null || !TYPE_XP_CHUNK.equals(JsonObjects.text(event, "type")))
			{
				return false;
			}
			String skill = evidenceString(evidenceObject(event), "skill", "");
			if (!isHitpointsSkillName(skill))
			{
				return false;
			}
		}
		return true;
	}

	public static String normalizeSkillKey(String skill)
	{
		if (skill == null)
		{
			return "";
		}
		return skill.trim().toUpperCase(Locale.ROOT);
	}

	public static long epochHour(long atMs)
	{
		long t = atMs;
		if (t < 0L)
		{
			t = 0L;
		}
		return t / HOUR_MS;
	}

	private static void mergeXp(Map<String, XpAgg> xpBySkillHour, JsonObject evidence, long at, long optimistic)
	{
		String skill = evidenceString(evidence, "skill", "");
		if (isCombatSkillName(skill))
		{
			return;
		}
		long xpDelta = evidenceLong(evidence, "xpDelta", 0L);
		if (xpDelta <= 0L)
		{
			return;
		}
		String skillKey = normalizeSkillKey(skill);
		if (skillKey.isEmpty())
		{
			return;
		}
		String key = skillKey + ":" + epochHour(at);
		XpAgg agg = xpBySkillHour.get(key);
		if (agg == null)
		{
			agg = new XpAgg();
			agg.emitSkill = skill.trim();
			xpBySkillHour.put(key, agg);
		}
		agg.xpDelta += xpDelta;
		agg.optimisticCredits += Math.max(0L, optimistic);
		agg.lastAt = Math.max(agg.lastAt, at);
	}

	private static void mergeLevelUp(Map<String, LevelAgg> levelBySkill, JsonObject evidence, long at, long optimistic)
	{
		String skill = evidenceString(evidence, "skill", "");
		int fromLevel = evidenceInt(evidence, "fromLevel", 0);
		int toLevel = evidenceInt(evidence, "toLevel", 0);
		if (toLevel <= fromLevel)
		{
			return;
		}
		String key = normalizeSkillKey(skill);
		if (key.isEmpty())
		{
			return;
		}
		LevelAgg agg = levelBySkill.get(key);
		if (agg == null)
		{
			agg = new LevelAgg();
			agg.emitSkill = skill.trim();
			agg.fromLevel = fromLevel;
			agg.toLevel = toLevel;
			agg.lastAt = at;
			agg.optimisticCredits = Math.max(0L, optimistic);
			levelBySkill.put(key, agg);
			return;
		}
		if (fromLevel < agg.fromLevel)
		{
			agg.fromLevel = fromLevel;
		}
		if (toLevel > agg.toLevel)
		{
			agg.toLevel = toLevel;
		}
		agg.optimisticCredits += Math.max(0L, optimistic);
		agg.lastAt = Math.max(agg.lastAt, at);
	}

	private static void mergeKill(Map<KillKey, KillAgg> kills, JsonObject evidence, long at, long optimistic)
	{
		String npcName = evidenceString(evidence, "npcName", "");
		int combatLevel = evidenceInt(evidence, "combatLevel", 0);
		int npcId = evidenceInt(evidence, "npcId", 0);
		int amount = Math.max(1, evidenceInt(evidence, "amount", 1));
		KillKey key = new KillKey(npcId, npcName, combatLevel, epochHour(at));
		KillAgg agg = kills.get(key);
		if (agg == null)
		{
			agg = new KillAgg();
			kills.put(key, agg);
		}
		agg.amount += amount;
		agg.optimisticCredits += Math.max(0L, optimistic);
		agg.lastAt = Math.max(agg.lastAt, at);
	}

	private static long priorityScore(JsonObject event)
	{
		String type = JsonObjects.text(event, "type");
		JsonObject evidence = evidenceObject(event);
		if (TYPE_LEVEL_UP.equals(type))
		{
			int span = Math.max(0, evidenceInt(evidence, "toLevel", 0) - evidenceInt(evidence, "fromLevel", 0));
			return 1_000_000_000L + span * 1_000L;
		}
		if (TYPE_XP_CHUNK.equals(type))
		{
			return 500_000_000L + Math.min(evidenceLong(evidence, "xpDelta", 0L), 400_000_000L);
		}
		if (TYPE_NPC_KILL.equals(type))
		{
			return 250_000_000L + Math.min(evidenceInt(evidence, "amount", 1), 200_000_000);
		}
		if (TYPE_ACTIVITY.equals(type))
		{
			return 100_000_000L;
		}
		return 1_000L;
	}

	private static JsonObject buildXp(String skill, long xpDelta, long at, long optimisticCredits)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill == null ? "" : skill);
		evidence.addProperty("xpDelta", xpDelta);
		return copyEvent(TYPE_XP_CHUNK, evidence, at, optimisticCredits);
	}

	private static JsonObject buildLevelUp(String skill, int fromLevel, int toLevel, long at, long optimisticCredits)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill == null ? "" : skill);
		evidence.addProperty("fromLevel", fromLevel);
		evidence.addProperty("toLevel", toLevel);
		return copyEvent(TYPE_LEVEL_UP, evidence, at, optimisticCredits);
	}

	private static JsonObject buildKill(int npcId, String npcName, int combatLevel, int amount, long at, long optimisticCredits)
	{
		JsonObject evidence = new JsonObject();
		if (npcId > 0)
		{
			evidence.addProperty("npcId", npcId);
		}
		if (npcName != null && !npcName.isEmpty())
		{
			evidence.addProperty("npcName", npcName);
		}
		evidence.addProperty("combatLevel", combatLevel);
		evidence.addProperty("amount", amount);
		return copyEvent(TYPE_NPC_KILL, evidence, at, optimisticCredits);
	}

	private static JsonObject copyEvent(String type, JsonObject evidence, long at, long optimisticCredits)
	{
		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.add("evidence", evidence == null ? new JsonObject() : evidence.deepCopy());
		event.addProperty("at", at);
		if (optimisticCredits > 0L)
		{
			event.addProperty(CLIENT_OPTIMISTIC_CREDITS, optimisticCredits);
		}
		return event;
	}

	public static long optimisticOf(JsonObject event)
	{
		if (event == null || !event.has(CLIENT_OPTIMISTIC_CREDITS) || event.get(CLIENT_OPTIMISTIC_CREDITS).isJsonNull())
		{
			return 0L;
		}
		try
		{
			return Math.max(0L, event.get(CLIENT_OPTIMISTIC_CREDITS).getAsLong());
		}
		catch (RuntimeException ex)
		{
			return 0L;
		}
	}

	public static JsonObject forWire(JsonObject event)
	{
		if (event == null)
		{
			return new JsonObject();
		}
		JsonObject copy = event.deepCopy();
		copy.remove(CLIENT_OPTIMISTIC_CREDITS);
		return copy;
	}

	private static Set<String> combatSkillSet(String... names)
	{
		Set<String> set = new HashSet<>();
		for (String name : names)
		{
			set.add(name);
		}
		return Set.copyOf(set);
	}

	private static long atOf(JsonObject event)
	{
		if (event != null && event.has("at") && !event.get("at").isJsonNull())
		{
			return event.get("at").getAsLong();
		}
		return System.currentTimeMillis();
	}

	private static final class XpAgg
	{
		private String emitSkill;
		private long xpDelta;
		private long lastAt;
		private long optimisticCredits;
	}

	private static final class LevelAgg
	{
		private String emitSkill;
		private int fromLevel;
		private int toLevel;
		private long lastAt;
		private long optimisticCredits;
	}

	private static final class KillAgg
	{
		private int amount;
		private long lastAt;
		private long optimisticCredits;
	}

	private static final class KillKey
	{
		private final int npcId;
		private final String npcName;
		private final int combatLevel;
		private final long epochHour;

		private KillKey(int npcId, String npcName, int combatLevel, long epochHour)
		{
			this.npcId = Math.max(0, npcId);
			this.npcName = npcName == null ? "" : npcName;
			this.combatLevel = combatLevel;
			this.epochHour = epochHour;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof KillKey))
			{
				return false;
			}
			KillKey that = (KillKey) o;
			return npcId == that.npcId
				&& combatLevel == that.combatLevel
				&& epochHour == that.epochHour
				&& Objects.equals(npcName, that.npcName);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(npcId, npcName, combatLevel, epochHour);
		}
	}

	private static final class Scored
	{
		private final int index;
		private final JsonObject event;
		private final long score;

		private Scored(int index, JsonObject event, long score)
		{
			this.index = index;
			this.event = event;
			this.score = score;
		}
	}
}

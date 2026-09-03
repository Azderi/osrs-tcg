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

/**
 * Stateless helper that merges raw credit-attest events (xp/level-up/npc-kill/activity) into fewer,
 * aggregated wire events, and picks which of those to send first when there are too many for one batch.
 * All methods are static and side-effect free (aside from mutating the {@code coalesced} list passed to
 * {@link #takePriorityBatch}); safe to call from any thread.
 */
public final class CreditAttestCoalescer
{
	public static final int MAX_BATCH = 100;
	public static final int MAX_KILL_AMOUNT = 500;
	public static final int EARLY_FLUSH_COALESCED = 80;
	public static final long HOUR_MS = 3_600_000L;
	/** Max optimistic credits payable from login-cached events folded into settle-hiscores. */
	public static final long MAX_LOGIN_CACHE_CREDITS = 500_000L;

	public static final String TYPE_NPC_KILL = "npc_kill";
	public static final String TYPE_XP_CHUNK = "xp_chunk";
	public static final String TYPE_LEVEL_UP = "level_up";
	public static final String TYPE_ACTIVITY = "activity";

	public static final String CLIENT_OPTIMISTIC_CREDITS = "_optimisticCredits";

	private static final Set<String> COMBAT_SKILLS_BLOCK_XP = Set.of(
		"ATTACK", "STRENGTH", "DEFENCE", "RANGED", "MAGIC");
	private static final String HITPOINTS_SKILL_KEY = "HITPOINTS";

	private CreditAttestCoalescer()
	{
	}

	/**
	 * Aggregates raw events: xp_chunk merges by skill+hour, level_up merges by skill (widening the
	 * from/to range), npc_kill merges by npc+combat level+hour then splits back into
	 * {@link #MAX_KILL_AMOUNT}-sized chunks, and activity (and any unrecognized type) passes through
	 * unmerged. Combat-skill xp is dropped (see {@link #isCombatSkillName}), as are zero-progress events.
	 */
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

	/** Convenience for checking the early-flush threshold without keeping the coalesced result. */
	public static int estimateCoalescedCount(List<JsonObject> rawEvents)
	{
		return coalesce(rawEvents).size();
	}

	/**
	 * Removes up to {@code max} events from {@code coalesced} (mutating it) and returns them as a batch,
	 * highest {@link #priorityScore} first, so the most valuable credits are sent when a flush can't fit
	 * everything in one request.
	 */
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

	/**
	 * Result of capping a coalesced login-cache batch by optimistic credit total.
	 * Payable events are ordered by {@link #priorityScore}; overflow is unpaid but still cleared on send.
	 */
	public static final class LoginCacheSplit
	{
		public final List<JsonObject> payable;
		public final long overflowCredits;
		public final int overflowEventCount;

		LoginCacheSplit(List<JsonObject> payable, long overflowCredits, int overflowEventCount)
		{
			this.payable = payable;
			this.overflowCredits = overflowCredits;
			this.overflowEventCount = overflowEventCount;
		}
	}

	/**
	 * Takes coalesced events in priority order until adding the next would exceed {@code maxOptimistic}.
	 * Does not mutate {@code coalesced}. Events beyond the cap become overflow (credits summed, not paid).
	 */
	public static LoginCacheSplit takePriorityUntilOptimistic(List<JsonObject> coalesced, long maxOptimistic)
	{
		if (coalesced == null || coalesced.isEmpty())
		{
			return new LoginCacheSplit(List.of(), 0L, 0);
		}
		long cap = Math.max(0L, maxOptimistic);
		List<Scored> scored = new ArrayList<>(coalesced.size());
		for (int i = 0; i < coalesced.size(); i++)
		{
			scored.add(new Scored(i, coalesced.get(i), priorityScore(coalesced.get(i))));
		}
		scored.sort(Comparator
			.comparingLong((Scored s) -> s.score).reversed()
			.thenComparingInt(s -> s.index));

		List<JsonObject> payable = new ArrayList<>();
		long used = 0L;
		long overflowCredits = 0L;
		int overflowEventCount = 0;
		for (Scored s : scored)
		{
			long cost = Math.max(0L, optimisticOf(s.event));
			if (payable.isEmpty() || used + cost <= cap)
			{
				payable.add(s.event);
				used += cost;
			}
			else
			{
				overflowCredits += cost;
				overflowEventCount += 1;
			}
		}
		return new LoginCacheSplit(List.copyOf(payable), overflowCredits, overflowEventCount);
	}

	/** True for the melee/ranged/magic/defence skills whose xp is never attested directly (kills are instead). */
	public static boolean isCombatSkillName(String skill)
	{
		return COMBAT_SKILLS_BLOCK_XP.contains(normalizeSkillKey(skill));
	}

	/** True for "HITPOINTS" or any skill name prefixed with it (e.g. boss-specific hitpoints variants). */
	public static boolean isHitpointsSkillName(String skill)
	{
		String key = normalizeSkillKey(skill);
		return key.equals(HITPOINTS_SKILL_KEY) || key.startsWith(HITPOINTS_SKILL_KEY + " ");
	}

	/**
	 * True if every event in {@code events} is a hitpoints xp_chunk. Used to hold back a batch that
	 * carries no real credit value (hitpoints xp is never awarded optimistic credits) rather than
	 * spending a flush cycle on it.
	 */
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

	/** Trims and upper-cases a skill name for use as a stable grouping/comparison key; null becomes "". */
	public static String normalizeSkillKey(String skill)
	{
		if (skill == null)
		{
			return "";
		}
		return skill.trim().toUpperCase(Locale.ROOT);
	}

	/** Floors an epoch-millis timestamp to its hour bucket, clamping negative input to 0. */
	public static long epochHour(long atMs)
	{
		long t = atMs;
		if (t < 0L)
		{
			t = 0L;
		}
		return t / HOUR_MS;
	}

	/** Adds one xp_chunk's delta into its skill+hour bucket; skips combat skills and non-positive deltas. */
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

	/** Widens a skill's level-up bucket to cover [min fromLevel, max toLevel]; skips non-progressing entries. */
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

	/** Adds one npc_kill's amount into its npc+combat-level+hour bucket. */
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

	/** Ranks events for {@link #takePriorityBatch}: level_up highest, then xp_chunk, npc_kill, activity. */
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

	/** Builds a wire-shaped xp_chunk event from aggregated values. */
	private static JsonObject buildXp(String skill, long xpDelta, long at, long optimisticCredits)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill == null ? "" : skill);
		evidence.addProperty("xpDelta", xpDelta);
		return copyEvent(TYPE_XP_CHUNK, evidence, at, optimisticCredits);
	}

	/** Builds a wire-shaped level_up event from aggregated values. */
	private static JsonObject buildLevelUp(String skill, int fromLevel, int toLevel, long at, long optimisticCredits)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill == null ? "" : skill);
		evidence.addProperty("fromLevel", fromLevel);
		evidence.addProperty("toLevel", toLevel);
		return copyEvent(TYPE_LEVEL_UP, evidence, at, optimisticCredits);
	}

	/** Builds a wire-shaped npc_kill event from aggregated values; omits npcId/npcName when unset. */
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

	/** Assembles a {type, evidence, at, [optimisticCredits]} event, deep-copying the evidence object. */
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

	/** Reads the client-side optimistic credit estimate stashed on an event, or 0 if absent/invalid. */
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

	/** Returns a copy of {@code event} with the client-only optimistic-credits field stripped for sending. */
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

	/** Reads an event's {@code at} timestamp, defaulting to now if missing/null. */
	private static long atOf(JsonObject event)
	{
		if (event != null && event.has("at") && !event.get("at").isJsonNull())
		{
			return event.get("at").getAsLong();
		}
		return System.currentTimeMillis();
	}

	/** Running total for one skill+hour xp_chunk bucket. */
	private static final class XpAgg
	{
		private String emitSkill;
		private long xpDelta;
		private long lastAt;
		private long optimisticCredits;
	}

	/** Widening from/to level range for one skill's level_up bucket. */
	private static final class LevelAgg
	{
		private String emitSkill;
		private int fromLevel;
		private int toLevel;
		private long lastAt;
		private long optimisticCredits;
	}

	/** Running total for one npc+combat-level+hour npc_kill bucket. */
	private static final class KillAgg
	{
		private int amount;
		private long lastAt;
		private long optimisticCredits;
	}

	/** Grouping key for npc_kill aggregation: npc identity, combat level, and hour bucket. */
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

		/** {@inheritDoc} */
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

		/** {@inheritDoc} */
		@Override
		public int hashCode()
		{
			return Objects.hash(npcId, npcName, combatLevel, epochHour);
		}
	}

	/** An event paired with its index in the source list and its {@link #priorityScore}, for sorting. */
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

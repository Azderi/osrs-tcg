package com.osrstcg.credit;

import com.osrstcg.state.SkillCreditBaseline;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
/** Live skill XP / level baselines and uncredited XP pools for {@link CreditAwardService}. */
final class SkillCreditSession
{
/** Highest level observed per skill so far this session (used to detect level-ups). */
	final Map<Skill, Integer> lastKnownLevels = new EnumMap<>(Skill.class);
/** Last-seen XP per skill, indexed by {@link Skill#ordinal()}; baseline for detecting XP gains. */
	final int[] previousSkillXp = new int[Skill.values().length];
/** XP earned but not yet converted to a full credit chunk, indexed by {@link Skill#ordinal()}. */
	final long[] uncreditedXpBySkill = new long[Skill.values().length];
/** Whether {@link #lastKnownLevels} has been populated from a logged-in client this session. */
	boolean skillLevelsInitialized;
/** Whether {@link #previousSkillXp} has been populated from a logged-in client this session. */
	boolean skillXpInitialized;
/** Slayer XP accrued since the last attempt to attest it (e.g. while the cloud session is offline). */
	long pendingSlayerXpToAttest;
/** Slayer XP short of a full {@link XpCreditMath#SLAYER_XP_PER_CHUNK} chunk, carried to the next gain. */
	long slayerXpRemainder;
/**
	 * XP earned under an activity XP rule (region-gated, e.g. Magic in the MTA, or non-combat) but short of a
	 * full chunk, keyed by the rule's activity id. Session-only: not persisted, so at most one partial chunk is
	 * lost on logout.
	 */
	final Map<String, Long> uncreditedXpByActivity = new HashMap<>();
/** Clears level/XP baselines so the next snapshot re-establishes them from the client. */
	void resetTracking()
	{
		lastKnownLevels.clear();
		skillLevelsInitialized = false;
		skillXpInitialized = false;
		Arrays.fill(previousSkillXp, 0);
	}
/** Discards all pending uncredited XP (main pool, Slayer pending/remainder, and activity-rule pools). */
	void clearUncreditedXpPool()
	{
		Arrays.fill(uncreditedXpBySkill, 0L);
		pendingSlayerXpToAttest = 0L;
		slayerXpRemainder = 0L;
		uncreditedXpByActivity.clear();
	}
/** Adds {@code xp} XP to the activity rule's pool for {@code activityId} and returns the new pool total. */
	long addActivityXp(String activityId, long xp)
	{
		if (activityId == null || activityId.isEmpty() || xp <= 0L)
		{
			return activityXpFor(activityId);
		}

		long next = activityXpFor(activityId) + xp;
		uncreditedXpByActivity.put(activityId, next);
		return next;
	}
/** Currently uncredited XP pooled for the activity rule {@code activityId} (0 if unknown/null). */
	long activityXpFor(String activityId)
	{
		if (activityId == null || activityId.isEmpty())
		{
			return 0L;
		}
		Long pooled = uncreditedXpByActivity.get(activityId);
		return pooled == null ? 0L : pooled;
	}
/** Removes {@code xp} XP from the activity rule's pool (clamped at 0), typically after crediting a chunk. */
	void subtractActivityXp(String activityId, long xp)
	{
		if (activityId == null || activityId.isEmpty() || xp <= 0L)
		{
			return;
		}
		long remaining = Math.max(0L, activityXpFor(activityId) - xp);
		if (remaining == 0L)
		{
			uncreditedXpByActivity.remove(activityId);
		}
		else
		{
			uncreditedXpByActivity.put(activityId, remaining);
		}
	}
/** Replaces the uncredited XP pool with a persisted baseline (e.g. restored after a profile reload). */
	void restoreUncreditedXp(SkillCreditBaseline saved)
	{
		Arrays.fill(uncreditedXpBySkill, 0L);
		pendingSlayerXpToAttest = 0L;
		slayerXpRemainder = 0L;
		uncreditedXpByActivity.clear();

		if (saved == null || !saved.isPresent())
		{
			return;
		}

		for (Map.Entry<String, Long> entry : saved.getUncreditedXpBySkill().entrySet())
		{
			Skill skill = skillByName(entry.getKey());
			if (skill == null || entry.getValue() == null)
			{
				continue;
			}
			int index = skill.ordinal();
			if (index >= 0 && index < uncreditedXpBySkill.length)
			{
				uncreditedXpBySkill[index] = Math.max(0L, entry.getValue());
			}
		}
	}
/** Adds {@code xp} XP to the skill's uncredited pool and returns the new pool total. */
	long addUncreditedXp(Skill skill, long xp)
	{
		if (skill == null || xp <= 0L)
		{
			return uncreditedXpFor(skill);
		}

		int index = skill.ordinal();
		if (index < 0 || index >= uncreditedXpBySkill.length)
		{
			return 0L;
		}

		uncreditedXpBySkill[index] += xp;
		return uncreditedXpBySkill[index];
	}
/** Currently uncredited XP pooled for {@code skill} (0 if unknown/null skill). */
	long uncreditedXpFor(Skill skill)
	{
		if (skill == null)
		{
			return 0L;
		}

		int index = skill.ordinal();
		if (index < 0 || index >= uncreditedXpBySkill.length)
		{
			return 0L;
		}

		return uncreditedXpBySkill[index];
	}
/** Removes {@code xp} XP from the skill's uncredited pool (clamped at 0), typically after crediting a chunk. */
	void subtractUncreditedXp(Skill skill, long xp)
	{
		if (skill == null || xp <= 0L)
		{
			return;
		}

		int index = skill.ordinal();
		if (index < 0 || index >= uncreditedXpBySkill.length)
		{
			return;
		}

		uncreditedXpBySkill[index] = Math.max(0L, uncreditedXpBySkill[index] - xp);
	}
/** Sum of uncredited XP pooled across all skills. */
	long totalUncreditedXp()
	{
		long total = 0L;
		for (long remainder : uncreditedXpBySkill)
		{
			total += remainder;
		}
		return total;
	}
/** Builds a persistable snapshot of current XP baselines and non-zero uncredited XP by skill name. */
	SkillCreditBaseline toBaseline()
	{
		Map<String, Long> uncreditedByName = new LinkedHashMap<>();
		Skill[] skills = Skill.values();
		for (int i = 0; i < uncreditedXpBySkill.length && i < skills.length; i++)
		{
			if (uncreditedXpBySkill[i] <= 0L)
			{
				continue;
			}

			Skill skill = skills[i];
			if (skill == null || skill.getName() == null)
			{
				continue;
			}
			uncreditedByName.put(skill.getName(), uncreditedXpBySkill[i]);
		}

		return SkillCreditBaseline.fromClientExperiences(
			Arrays.copyOf(previousSkillXp, previousSkillXp.length),
			uncreditedByName);
	}
/** Snapshots both XP and level baselines from the client, if logged in. */
	void snapshotBaselinesIfLoggedIn(Client client)
	{
		snapshotXpIfLoggedIn(client);
		snapshotSkillLevelsIfLoggedIn(client);
	}
/**
	 * Snapshots per-skill XP from the client into {@link #previousSkillXp}, if logged in. Once initialized,
	 * only raises the baseline (never lowers it) so a transient client read can't roll it back.
	 */
	void snapshotXpIfLoggedIn(Client client)
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int[] experiences = client.getSkillExperiences();
		int n = Math.min(experiences.length, previousSkillXp.length);
		if (!skillXpInitialized)
		{
			System.arraycopy(experiences, 0, previousSkillXp, 0, n);
		}
		else
		{
			// Never lower an already-established baseline from a transient client snapshot.
			for (int i = 0; i < n; i++)
			{
				if (experiences[i] > previousSkillXp[i])
				{
					previousSkillXp[i] = experiences[i];
				}
			}
		}
		skillXpInitialized = true;
	}
/**
	 * Snapshots per-skill levels from the client into {@link #lastKnownLevels}, if logged in. Only raises an
	 * already-known level, and skips the Overall pseudo-skill.
	 */
	void snapshotSkillLevelsIfLoggedIn(Client client)
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (!skillLevelsInitialized)
		{
			lastKnownLevels.clear();
		}
		for (Skill skill : Skill.values())
		{
			if (CreditAwardService.isOverallSkill(skill))
			{
				continue;
			}

			int level = LevelUpCreditMath.levelForXp(client.getSkillExperience(skill));
			Integer previous = lastKnownLevels.get(skill);
			if (previous == null || level > previous)
			{
				lastKnownLevels.put(skill, level);
			}
		}
		skillLevelsInitialized = true;
	}
/** Looks up a {@link Skill} by its display name (case-insensitive), or {@code null} if not found. */
	private static Skill skillByName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return null;
		}

		for (Skill skill : Skill.values())
		{
			if (skill != null && name.equalsIgnoreCase(skill.getName()))
			{
				return skill;
			}
		}
		return null;
	}
}

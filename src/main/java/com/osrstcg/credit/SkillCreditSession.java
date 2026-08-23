package com.osrstcg.credit;

import com.osrstcg.state.SkillCreditBaseline;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;

/** Live skill XP / level baselines and uncredited XP pools for {@link CreditAwardService}. */
final class SkillCreditSession
{
	final Map<Skill, Integer> lastKnownLevels = new EnumMap<>(Skill.class);
	final int[] previousSkillXp = new int[Skill.values().length];
	final long[] uncreditedXpBySkill = new long[Skill.values().length];
	boolean skillLevelsInitialized;
	boolean skillXpInitialized;
	long pendingSlayerXpToAttest;
	long slayerOptimisticRemainder;

	void resetTracking()
	{
		lastKnownLevels.clear();
		skillLevelsInitialized = false;
		skillXpInitialized = false;
		Arrays.fill(previousSkillXp, 0);
	}

	void clearUncreditedXpPool()
	{
		Arrays.fill(uncreditedXpBySkill, 0L);
		pendingSlayerXpToAttest = 0L;
		slayerOptimisticRemainder = 0L;
	}

	void restoreUncreditedXp(SkillCreditBaseline saved)
	{
		Arrays.fill(uncreditedXpBySkill, 0L);
		pendingSlayerXpToAttest = 0L;
		slayerOptimisticRemainder = 0L;

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

	long totalUncreditedXp()
	{
		long total = 0L;
		for (long remainder : uncreditedXpBySkill)
		{
			total += remainder;
		}
		return total;
	}

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

	void snapshotSkillBaselinesIfLoggedIn(Client client)
	{
		snapshotSkillExperiencesIfLoggedIn(client);
		snapshotSkillLevelsIfLoggedIn(client);
	}

	void snapshotSkillExperiencesIfLoggedIn(Client client)
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

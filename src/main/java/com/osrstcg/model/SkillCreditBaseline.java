package com.osrstcg.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import net.runelite.api.Skill;

/**
 * Persisted skill XP baselines used to retroactively award credits after login settle.
 * <ul>
 *   <li>{@link #missing()} — older schema lacked the field; rewrite JSON on load</li>
 *   <li>{@link #absent()} — field present but no settled capture yet; no retro awards</li>
 *   <li>{@link #of} — settled snapshot from a prior session</li>
 * </ul>
 */
public final class SkillCreditBaseline
{
	private static final SkillCreditBaseline MISSING = new SkillCreditBaseline(Kind.MISSING, Map.of(), 0L);
	private static final SkillCreditBaseline ABSENT = new SkillCreditBaseline(Kind.ABSENT, Map.of(), 0L);

	private enum Kind
	{
		MISSING,
		ABSENT,
		PRESENT
	}

	private final Kind kind;
	private final Map<String, Integer> skillXpByName;
	private final long uncreditedXp;

	private SkillCreditBaseline(Kind kind, Map<String, Integer> skillXpByName, long uncreditedXp)
	{
		this.kind = kind;
		this.skillXpByName = skillXpByName;
		this.uncreditedXp = Math.max(0L, uncreditedXp);
	}

	/** Older profile JSON omitted {@code skillCreditBaseline}; persist a placeholder on load. */
	public static SkillCreditBaseline missing()
	{
		return MISSING;
	}

	/** Schema field exists (or was written) but no settled skill snapshot yet. */
	public static SkillCreditBaseline absent()
	{
		return ABSENT;
	}

	public static SkillCreditBaseline of(Map<String, Integer> skillXpByName, long uncreditedXp)
	{
		Map<String, Integer> copy = new LinkedHashMap<>();
		if (skillXpByName != null)
		{
			for (Map.Entry<String, Integer> e : skillXpByName.entrySet())
			{
				if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null)
				{
					continue;
				}
				copy.put(e.getKey(), Math.max(0, e.getValue()));
			}
		}
		if (copy.isEmpty())
		{
			return absent();
		}
		return new SkillCreditBaseline(Kind.PRESENT, Collections.unmodifiableMap(copy), uncreditedXp);
	}

	public static SkillCreditBaseline fromClientExperiences(int[] experiences, long uncreditedXp)
	{
		Map<String, Integer> byName = new LinkedHashMap<>();
		Skill[] skills = Skill.values();
		int n = experiences == null ? 0 : Math.min(experiences.length, skills.length);
		for (int i = 0; i < n; i++)
		{
			Skill skill = skills[i];
			if (skill == null || skill.getName() == null)
			{
				continue;
			}
			byName.put(skill.getName(), Math.max(0, experiences[i]));
		}
		return of(byName, uncreditedXp);
	}

	/** True when a prior settled snapshot exists and can be used for retroactive awards. */
	public boolean isPresent()
	{
		return kind == Kind.PRESENT;
	}

	/** True when loaded JSON lacked the skill baseline field (schema upgrade needed on disk). */
	public boolean needsSchemaUpgradePersist()
	{
		return kind == Kind.MISSING;
	}

	public long getUncreditedXp()
	{
		return uncreditedXp;
	}

	public Map<String, Integer> getSkillXpByName()
	{
		return skillXpByName;
	}

	public OptionalInt xpFor(Skill skill)
	{
		if (kind != Kind.PRESENT || skill == null || skill.getName() == null)
		{
			return OptionalInt.empty();
		}
		Integer xp = skillXpByName.get(skill.getName());
		return xp == null ? OptionalInt.empty() : OptionalInt.of(xp);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof SkillCreditBaseline))
		{
			return false;
		}
		SkillCreditBaseline that = (SkillCreditBaseline) o;
		return kind == that.kind
			&& uncreditedXp == that.uncreditedXp
			&& Objects.equals(skillXpByName, that.skillXpByName);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(kind, skillXpByName, uncreditedXp);
	}
}

package com.osrstcg.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import net.runelite.api.Skill;

/**
 * Persisted skill XP snapshot for live-session credit tracking (uncredited XP remainder + last seen XP).
 * Offline/retro credits are awarded server-side via {@code POST /credits/settle-hiscores}.
 * <ul>
 *   <li>{@link #missing()} - older schema lacked the field; rewrite JSON on load</li>
 *   <li>{@link #absent()} - field present but no live capture yet</li>
 *   <li>{@link #of} - live capture from a prior session</li>
 * </ul>
 */
public final class SkillCreditBaseline
{
	private static final SkillCreditBaseline MISSING = new SkillCreditBaseline(Kind.MISSING, Map.of(), Map.of());
	private static final SkillCreditBaseline ABSENT = new SkillCreditBaseline(Kind.ABSENT, Map.of(), Map.of());

	private enum Kind
	{
		MISSING,
		ABSENT,
		PRESENT
	}

	private final Kind kind;
	private final Map<String, Integer> skillXpByName;
	private final Map<String, Long> uncreditedXpBySkill;

	private SkillCreditBaseline(Kind kind, Map<String, Integer> skillXpByName, Map<String, Long> uncreditedXpBySkill)
	{
		this.kind = kind;
		this.skillXpByName = skillXpByName;
		this.uncreditedXpBySkill = uncreditedXpBySkill;
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

	public static SkillCreditBaseline of(Map<String, Integer> skillXpByName, Map<String, Long> uncreditedXpBySkill)
	{
		Map<String, Integer> xpCopy = new LinkedHashMap<>();
		if (skillXpByName != null)
		{
			for (Map.Entry<String, Integer> e : skillXpByName.entrySet())
			{
				if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null)
				{
					continue;
				}
				xpCopy.put(e.getKey(), Math.max(0, e.getValue()));
			}
		}
		Map<String, Long> uncreditedCopy = copyUncreditedXpBySkill(uncreditedXpBySkill);
		if (xpCopy.isEmpty())
		{
			return absent();
		}
		return new SkillCreditBaseline(Kind.PRESENT, Collections.unmodifiableMap(xpCopy), uncreditedCopy);
	}

	public static SkillCreditBaseline fromClientExperiences(int[] experiences, Map<String, Long> uncreditedXpBySkill)
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
		return of(byName, uncreditedXpBySkill);
	}

	private static Map<String, Long> copyUncreditedXpBySkill(Map<String, Long> uncreditedXpBySkill)
	{
		Map<String, Long> copy = new LinkedHashMap<>();
		if (uncreditedXpBySkill == null)
		{
			return Collections.unmodifiableMap(copy);
		}
		for (Map.Entry<String, Long> e : uncreditedXpBySkill.entrySet())
		{
			if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null)
			{
				continue;
			}
			long remainder = Math.max(0L, e.getValue());
			if (remainder > 0L)
			{
				copy.put(e.getKey(), remainder);
			}
		}
		return Collections.unmodifiableMap(copy);
	}

	/** True when a prior live capture exists (used for restoring uncredited XP remainder). */
	public boolean isPresent()
	{
		return kind == Kind.PRESENT;
	}

	/** True when loaded JSON lacked the skill baseline field (schema upgrade needed on disk). */
	public boolean needsSchemaUpgradePersist()
	{
		return kind == Kind.MISSING;
	}

	public Map<String, Long> getUncreditedXpBySkill()
	{
		return uncreditedXpBySkill;
	}

	public Map<String, Integer> getSkillXpByName()
	{
		return skillXpByName;
	}

	public OptionalLong uncreditedXpFor(Skill skill)
	{
		if (kind != Kind.PRESENT || skill == null || skill.getName() == null)
		{
			return OptionalLong.empty();
		}
		Long remainder = uncreditedXpBySkill.get(skill.getName());
		return remainder == null ? OptionalLong.empty() : OptionalLong.of(remainder);
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
			&& Objects.equals(skillXpByName, that.skillXpByName)
			&& Objects.equals(uncreditedXpBySkill, that.uncreditedXpBySkill);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(kind, skillXpByName, uncreditedXpBySkill);
	}
}

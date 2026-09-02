package com.osrstcg.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import net.runelite.api.Skill;

/**
 * Immutable snapshot of a player's per-skill XP totals (and any not-yet-credited XP remainder per skill)
 * captured as the reference point for awarding future skill-XP credits. Distinguishes "no baseline saved
 * yet" ({@link #missing()}) from "baseline saved but empty" ({@link #absent()}) so callers know whether a
 * profile-save schema upgrade is needed.
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

	/** No baseline has been persisted yet for this profile; the caller should upgrade the save schema. */
	public static SkillCreditBaseline missing()
	{
		return MISSING;
	}

	/** Baseline was persisted but has no skill XP recorded (e.g. player has no tracked skills). */
	public static SkillCreditBaseline absent()
	{
		return ABSENT;
	}

	/**
	 * Builds a present baseline from raw skill-XP and uncredited-remainder maps, discarding null/blank
	 * keys and clamping values to non-negative. Falls back to {@link #absent()} when the XP map is empty
	 * after filtering.
	 */
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

	/** Builds a present baseline from the RuneLite client's raw per-skill XP array, indexed by {@link Skill#values()}. */
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

	/** Copies the uncredited-XP map, dropping null/blank keys, null values, and non-positive remainders. */
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

	/** True when this baseline has recorded skill XP (as opposed to {@link #missing()} or {@link #absent()}). */
	public boolean isPresent()
	{
		return kind == Kind.PRESENT;
	}

	/** True when this is a {@link #missing()} baseline and the profile save should be upgraded to persist one. */
	public boolean needsSchemaUpgradePersist()
	{
		return kind == Kind.MISSING;
	}

	/** Returns the uncredited XP remainder per skill name (unmodifiable). */
	public Map<String, Long> getUncreditedXpBySkill()
	{
		return uncreditedXpBySkill;
	}

	/** Returns the baseline XP per skill name (unmodifiable). */
	public Map<String, Integer> getSkillXpByName()
	{
		return skillXpByName;
	}

	/** Returns the uncredited XP remainder for a skill, or empty when this baseline isn't present or the skill is unknown. */
	public OptionalLong uncreditedXpFor(Skill skill)
	{
		if (kind != Kind.PRESENT || skill == null || skill.getName() == null)
		{
			return OptionalLong.empty();
		}
		Long remainder = uncreditedXpBySkill.get(skill.getName());
		return remainder == null ? OptionalLong.empty() : OptionalLong.of(remainder);
	}

	/** Equal when kind and both maps match. */
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

	/** Consistent with {@link #equals}. */
	@Override
	public int hashCode()
	{
		return Objects.hash(kind, skillXpByName, uncreditedXpBySkill);
	}
}

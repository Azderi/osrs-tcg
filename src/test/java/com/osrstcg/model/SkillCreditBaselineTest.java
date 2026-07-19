package com.osrstcg.model;

import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Assert;
import org.junit.Test;

public class SkillCreditBaselineTest
{
	@Test
	public void missingNeedsUpgradeAndIsNotPresent()
	{
		SkillCreditBaseline missing = SkillCreditBaseline.missing();
		Assert.assertTrue(missing.needsSchemaUpgradePersist());
		Assert.assertFalse(missing.isPresent());
		Assert.assertFalse(missing.xpFor(Skill.ATTACK).isPresent());
	}

	@Test
	public void fromClientExperiencesCapturesBySkillName()
	{
		int[] xp = new int[Skill.values().length];
		xp[Skill.COOKING.ordinal()] = 12_345;
		SkillCreditBaseline baseline = SkillCreditBaseline.fromClientExperiences(xp, 99L);
		Assert.assertTrue(baseline.isPresent());
		Assert.assertEquals(99L, baseline.getUncreditedXp());
		Assert.assertEquals(12_345, baseline.xpFor(Skill.COOKING).orElse(-1));
		Assert.assertEquals(0, baseline.xpFor(Skill.ATTACK).orElse(-1));
	}

	@Test
	public void ofEmptyMapBecomesAbsent()
	{
		SkillCreditBaseline baseline = SkillCreditBaseline.of(Map.of(), 5L);
		Assert.assertFalse(baseline.isPresent());
		Assert.assertFalse(baseline.needsSchemaUpgradePersist());
	}
}

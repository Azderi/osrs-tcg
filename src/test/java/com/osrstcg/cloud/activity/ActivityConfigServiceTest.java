package com.osrstcg.cloud.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstcg.cloud.activity.ActivityConfigModels.ActivityConfigDto;
import com.osrstcg.cloud.activity.ActivityConfigModels.NonCombatXpRuleDto;
import com.osrstcg.cloud.activity.ActivityConfigModels.XpRegionRuleDto;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Skill;
import org.junit.Test;

public class ActivityConfigServiceTest
{
	/** Map region of the Enchanting Chamber, Creature Graveyard and Alchemists' Playground (planes 0-2). */
	private static final int MTA_ROOMS_REGION = 13462;
	/** Map region of the Telekinetic Theatre mazes (planes 0-2), directly north of the main rooms. */
	private static final int MTA_TELEKINETIC_REGION = 13463;
	/** Surface region of the arena entrance hall with the room portals; not a training area. */
	private static final int MTA_LOBBY_REGION = 13363;
	private static final List<Integer> MTA_REGIONS = List.of(MTA_ROOMS_REGION, MTA_TELEKINETIC_REGION);

	@Test
	public void compilesXpRegionRuleAndMatchesSkillInEveryListedRegion()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.version = "v1";
		dto.xpRegionRules = List.of(rule("mta_magic", "Magic", MTA_REGIONS, 1000L, 100L));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertEquals(1, compiled.getXpRegionRules().size());
		for (int region : MTA_REGIONS)
		{
			CompiledActivityConfig.CompiledXpRegionRule matched = compiled.findXpRegionRule(Skill.MAGIC, region);
			assertNotNull(matched);
			assertEquals("mta_magic", matched.getActivityId());
			assertEquals(Skill.MAGIC, matched.getSkill());
			assertEquals(1000L, matched.getXpPerChunk());
			assertEquals(100L, matched.getCreditsPerChunk());
		}
	}

	@Test
	public void xpRegionRuleDoesNotMatchOtherRegionOrSkill()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.xpRegionRules = List.of(rule("mta_magic", "Magic", MTA_REGIONS, 1000L, 100L));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertNull(compiled.findXpRegionRule(Skill.MAGIC, MTA_LOBBY_REGION));
		assertNull(compiled.findXpRegionRule(Skill.MAGIC, MTA_TELEKINETIC_REGION + 1));
		assertNull(compiled.findXpRegionRule(Skill.RANGED, MTA_ROOMS_REGION));
		assertNull(compiled.findXpRegionRule(null, MTA_ROOMS_REGION));
	}

	@Test
	public void skillNameIsCaseInsensitive()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.xpRegionRules = List.of(rule("mta_magic", "mAgIc", MTA_REGIONS, 1000L, 100L));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertNotNull(compiled.findXpRegionRule(Skill.MAGIC, MTA_ROOMS_REGION));
	}

	@Test
	public void invalidXpRegionRulesAreDropped()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.xpRegionRules = Arrays.asList(
			null,
			rule("", "Magic", MTA_REGIONS, 1000L, 100L),
			rule("unknown_skill", "Not a skill", MTA_REGIONS, 1000L, 100L),
			rule("no_regions", "Magic", List.of(), 1000L, 100L),
			rule("null_region_only", "Magic", Arrays.asList((Integer) null), 1000L, 100L),
			rule("zero_chunk", "Magic", MTA_REGIONS, 0L, 100L),
			rule("valid", "Magic", MTA_REGIONS, 500L, 50L));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertEquals(1, compiled.getXpRegionRules().size());
		assertEquals("valid", compiled.getXpRegionRules().get(0).getActivityId());
	}

	@Test
	public void negativeCreditsClampToZero()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.xpRegionRules = List.of(rule("mta_magic", "Magic", MTA_REGIONS, 1000L, -5L));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertEquals(0L, compiled.getXpRegionRules().get(0).getCreditsPerChunk());
	}

	@Test
	public void missingXpRegionRulesCompilesToEmptyList()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.xpRegionRules = null;

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertTrue(compiled.getXpRegionRules().isEmpty());
		assertNull(compiled.findXpRegionRule(Skill.MAGIC, MTA_ROOMS_REGION));
	}

	@Test
	public void emptyConfigHasNoXpRegionRules()
	{
		assertTrue(CompiledActivityConfig.EMPTY.getXpRegionRules().isEmpty());
		assertNull(CompiledActivityConfig.EMPTY.findXpRegionRule(Skill.MAGIC, MTA_ROOMS_REGION));
		assertTrue(CompiledActivityConfig.EMPTY.getNonCombatXpRules().isEmpty());
		assertNull(CompiledActivityConfig.EMPTY.findNonCombatXpRule(Skill.MAGIC));
	}

	@Test
	public void compilesNonCombatXpRuleWithDefaultLockout()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.nonCombatXpRules = List.of(nonCombatRule("magic_noncombat", "Magic", 1000L, 100L, null));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertEquals(1, compiled.getNonCombatXpRules().size());
		CompiledActivityConfig.CompiledNonCombatXpRule matched = compiled.findNonCombatXpRule(Skill.MAGIC);
		assertNotNull(matched);
		assertEquals("magic_noncombat", matched.getActivityId());
		assertEquals(Skill.MAGIC, matched.getSkill());
		assertEquals(1000L, matched.getXpPerChunk());
		assertEquals(100L, matched.getCreditsPerChunk());
		assertEquals("Non-combat Magic", matched.getLabel());
		assertEquals(
			CompiledActivityConfig.CompiledNonCombatXpRule.DEFAULT_COMBAT_LOCKOUT_TICKS,
			matched.getCombatLockoutTicks());
	}

	@Test
	public void nonCombatXpRuleHonoursExplicitLockoutAndClampsNegative()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.nonCombatXpRules = List.of(
			nonCombatRule("magic_noncombat", "Magic", 1000L, 100L, 15),
			nonCombatRule("ranged_noncombat", "Ranged", 1000L, 100L, -3));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertEquals(15, compiled.findNonCombatXpRule(Skill.MAGIC).getCombatLockoutTicks());
		assertEquals(0, compiled.findNonCombatXpRule(Skill.RANGED).getCombatLockoutTicks());
	}

	@Test
	public void nonCombatXpRuleDoesNotMatchOtherSkill()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.nonCombatXpRules = List.of(nonCombatRule("magic_noncombat", "Magic", 1000L, 100L, null));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertNull(compiled.findNonCombatXpRule(Skill.RANGED));
		assertNull(compiled.findNonCombatXpRule(Skill.ATTACK));
		assertNull(compiled.findNonCombatXpRule(null));
	}

	@Test
	public void invalidNonCombatXpRulesAreDropped()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.nonCombatXpRules = Arrays.asList(
			null,
			nonCombatRule("", "Magic", 1000L, 100L, null),
			nonCombatRule("unknown_skill", "Not a skill", 1000L, 100L, null),
			nonCombatRule("zero_chunk", "Magic", 0L, 100L, null),
			nonCombatRule("valid", "Magic", 500L, 50L, null));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertEquals(1, compiled.getNonCombatXpRules().size());
		assertEquals("valid", compiled.getNonCombatXpRules().get(0).getActivityId());
	}

	@Test
	public void missingNonCombatXpRulesCompilesToEmptyList()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.nonCombatXpRules = null;

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertTrue(compiled.getNonCombatXpRules().isEmpty());
	}

	@Test
	public void regionAndNonCombatRulesCompileSideBySide()
	{
		ActivityConfigDto dto = new ActivityConfigDto();
		dto.xpRegionRules = List.of(rule("mta_magic", "Magic", MTA_REGIONS, 1000L, 100L));
		dto.nonCombatXpRules = List.of(nonCombatRule("magic_noncombat", "Magic", 1000L, 100L, null));

		CompiledActivityConfig compiled = ActivityConfigService.compile(dto);

		assertEquals("mta_magic", compiled.findXpRegionRule(Skill.MAGIC, MTA_ROOMS_REGION).getActivityId());
		assertEquals("magic_noncombat", compiled.findNonCombatXpRule(Skill.MAGIC).getActivityId());
	}

	private static NonCombatXpRuleDto nonCombatRule(String activityId, String skill, long xpPerChunk,
		long credits, Integer lockoutTicks)
	{
		NonCombatXpRuleDto dto = new NonCombatXpRuleDto();
		dto.activityId = activityId;
		dto.skill = skill;
		dto.xpPerChunk = xpPerChunk;
		dto.credits = credits;
		dto.label = "Non-combat Magic";
		dto.combatLockoutTicks = lockoutTicks;
		return dto;
	}

	private static XpRegionRuleDto rule(String activityId, String skill, List<Integer> regionIds,
		long xpPerChunk, long credits)
	{
		XpRegionRuleDto dto = new XpRegionRuleDto();
		dto.activityId = activityId;
		dto.skill = skill;
		dto.regionIds = regionIds;
		dto.xpPerChunk = xpPerChunk;
		dto.credits = credits;
		dto.label = "Magic Training Arena";
		return dto;
	}
}

package com.osrstcg.cloud.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstcg.cloud.activity.ActivityConfigModels.ActivityConfigDto;
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

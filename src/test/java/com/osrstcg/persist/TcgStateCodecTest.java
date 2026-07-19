package com.osrstcg.persist;

import com.google.gson.Gson;
import com.osrstcg.model.SkillCreditBaseline;
import com.osrstcg.model.TcgState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class TcgStateCodecTest
{
	private final TcgStateCodec codec = new TcgStateCodec(new Gson());

	@Test
	public void fromJsonUpgradesMissingSkillBaselineAndProfileMeta()
	{
		String legacy = "{"
			+ "\"schemaVersion\":3,"
			+ "\"credits\":500,"
			+ "\"openedPacks\":1,"
			+ "\"cardInstances\":[]"
			+ "}";

		TcgState state = codec.fromJson(legacy);
		Assert.assertEquals(TcgState.CURRENT_SCHEMA_VERSION, state.getSchemaVersion());
		Assert.assertEquals(500L, state.getEconomyState().getCredits());
		Assert.assertTrue(state.getSkillCreditBaseline().needsSchemaUpgradePersist());
		Assert.assertFalse(state.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(0L, state.getTotalCreditsGained());
		Assert.assertEquals(0L, state.getProfileCreatedAtUnix());

		String upgraded = codec.toJson(state.withProfileCreatedAtUnix(1_700_000_000L));
		Assert.assertTrue(upgraded.contains("\"schemaVersion\":5") || upgraded.contains("\"schemaVersion\": 5"));
		Assert.assertTrue(upgraded.contains("skillCreditBaseline"));
		Assert.assertTrue(upgraded.contains("totalCreditsGained"));
		Assert.assertTrue(upgraded.contains("profileCreatedAtUnix"));
		Assert.assertTrue(upgraded.contains("profileSavedAtUnix"));

		TcgState reloaded = codec.fromJson(upgraded);
		Assert.assertFalse(reloaded.getSkillCreditBaseline().needsSchemaUpgradePersist());
		Assert.assertFalse(reloaded.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(1_700_000_000L, reloaded.getProfileCreatedAtUnix());
		Assert.assertEquals(0L, reloaded.getTotalCreditsGained());
		Assert.assertEquals(0L, reloaded.getProfileSavedAtUnix());
	}

	@Test
	public void roundTripsProfileSavedAtUnix()
	{
		TcgState state = TcgState.empty()
			.withProfileSavedAtUnix(1_700_000_100L);
		TcgState loaded = codec.fromJson(codec.toJson(state));
		Assert.assertEquals(1_700_000_100L, loaded.getProfileSavedAtUnix());
	}

	@Test
	public void roundTripsPresentSkillBaselineBySkillName()
	{
		Map<String, Integer> xp = new LinkedHashMap<>();
		xp.put("Attack", 1000);
		xp.put("Cooking", 55_000);
		TcgState state = TcgState.empty()
			.withCredits(10L)
			.withTotalCreditsGained(1_234L)
			.withSkillCreditBaseline(SkillCreditBaseline.of(xp, 250L));

		TcgState loaded = codec.fromJson(codec.toJson(state));
		Assert.assertTrue(loaded.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(250L, loaded.getSkillCreditBaseline().getUncreditedXp());
		Assert.assertEquals(1000, loaded.getSkillCreditBaseline().xpFor(net.runelite.api.Skill.ATTACK).orElse(-1));
		Assert.assertEquals(55_000, loaded.getSkillCreditBaseline().xpFor(net.runelite.api.Skill.COOKING).orElse(-1));
		Assert.assertEquals(1_234L, loaded.getTotalCreditsGained());
		Assert.assertTrue(loaded.getProfileCreatedAtUnix() > 0L);
	}
}

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
	public void fromJsonUpgradesMissingSkillBaselineToSchemaFour()
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

		String upgraded = codec.toJson(state);
		Assert.assertTrue(upgraded.contains("\"schemaVersion\":4") || upgraded.contains("\"schemaVersion\": 4"));
		Assert.assertTrue(upgraded.contains("skillCreditBaseline"));

		TcgState reloaded = codec.fromJson(upgraded);
		Assert.assertFalse(reloaded.getSkillCreditBaseline().needsSchemaUpgradePersist());
		Assert.assertFalse(reloaded.getSkillCreditBaseline().isPresent());
	}

	@Test
	public void roundTripsPresentSkillBaselineBySkillName()
	{
		Map<String, Integer> xp = new LinkedHashMap<>();
		xp.put("Attack", 1000);
		xp.put("Cooking", 55_000);
		TcgState state = TcgState.empty()
			.withCredits(10L)
			.withSkillCreditBaseline(SkillCreditBaseline.of(xp, 250L));

		TcgState loaded = codec.fromJson(codec.toJson(state));
		Assert.assertTrue(loaded.getSkillCreditBaseline().isPresent());
		Assert.assertEquals(250L, loaded.getSkillCreditBaseline().getUncreditedXp());
		Assert.assertEquals(1000, loaded.getSkillCreditBaseline().xpFor(net.runelite.api.Skill.ATTACK).orElse(-1));
		Assert.assertEquals(55_000, loaded.getSkillCreditBaseline().xpFor(net.runelite.api.Skill.COOKING).orElse(-1));
	}
}

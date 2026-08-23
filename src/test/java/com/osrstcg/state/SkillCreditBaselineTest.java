package com.osrstcg.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstcg.persist.TcgStateCodec;
import com.osrstcg.state.TcgState;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Test;

public class SkillCreditBaselineTest
{
	@Test
	public void ofStoresOnlyNonZeroUncreditedRemainders()
	{
		Map<String, Integer> skillXp = new LinkedHashMap<>();
		skillXp.put("Woodcutting", 10_000);
		Map<String, Long> uncredited = new LinkedHashMap<>();
		uncredited.put("Woodcutting", 450L);
		uncredited.put("Mining", 0L);

		SkillCreditBaseline baseline = SkillCreditBaseline.of(skillXp, uncredited);

		assertTrue(baseline.isPresent());
		assertEquals(450L, baseline.uncreditedXpFor(Skill.WOODCUTTING).orElse(-1L));
		assertFalse(baseline.uncreditedXpFor(Skill.MINING).isPresent());
		assertEquals(450L, baseline.getUncreditedXp());
	}

	@Test
	public void legacyGlobalUncreditedXpDiscardedOnCodecLoad()
	{
		String json = "{"
			+ "\"schemaVersion\":6,"
			+ "\"credits\":0,"
			+ "\"skillCreditBaseline\":{"
			+ "\"skillXp\":{\"Woodcutting\":10000},"
			+ "\"uncreditedXp\":800"
			+ "}"
			+ "}";

		TcgStateCodec codec = new TcgStateCodec(new Gson());
		TcgState state = codec.fromJson(json);
		SkillCreditBaseline baseline = state.getSkillCreditBaseline();

		assertTrue(baseline.isPresent());
		assertTrue(baseline.getUncreditedXpBySkill().isEmpty());
		assertEquals(0L, baseline.getUncreditedXp());
	}

	@Test
	public void codecRoundTripPersistsPerSkillUncreditedXp()
	{
		Map<String, Integer> skillXp = new LinkedHashMap<>();
		skillXp.put("Woodcutting", 10_000);
		skillXp.put("Mining", 20_000);
		Map<String, Long> uncredited = new LinkedHashMap<>();
		uncredited.put("Woodcutting", 450L);
		uncredited.put("Mining", 820L);
		SkillCreditBaseline baseline = SkillCreditBaseline.of(skillXp, uncredited);

		TcgState state = TcgState.empty().withSkillCreditBaseline(baseline);
		TcgStateCodec codec = new TcgStateCodec(new Gson());
		TcgState loaded = codec.fromJson(codec.toJson(state));

		SkillCreditBaseline restored = loaded.getSkillCreditBaseline();
		assertEquals(450L, restored.uncreditedXpFor(Skill.WOODCUTTING).orElse(-1L));
		assertEquals(820L, restored.uncreditedXpFor(Skill.MINING).orElse(-1L));
	}
}

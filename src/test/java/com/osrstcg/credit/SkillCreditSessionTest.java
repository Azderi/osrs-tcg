package com.osrstcg.credit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstcg.state.SkillCreditBaseline;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Test;

public class SkillCreditSessionTest
{
	@Test
	public void perSkillBucketsStayIsolated()
	{
		SkillCreditSession session = new SkillCreditSession();
		session.skillXpInitialized = true;
		Arrays.fill(session.previousSkillXp, 1000);

		session.addUncreditedXp(Skill.WOODCUTTING, 700L);
		session.addUncreditedXp(Skill.MINING, 300L);

		assertEquals(700L, session.uncreditedXpFor(Skill.WOODCUTTING));
		assertEquals(300L, session.uncreditedXpFor(Skill.MINING));
		assertFalse(session.uncreditedXpFor(Skill.WOODCUTTING) >= XpCreditMath.XP_PER_CREDIT_CHUNK);
		assertFalse(session.uncreditedXpFor(Skill.MINING) >= XpCreditMath.XP_PER_CREDIT_CHUNK);
	}

	@Test
	public void singleSkillChunkAwardLeavesOtherBucketsUntouched()
	{
		SkillCreditSession session = new SkillCreditSession();
		session.addUncreditedXp(Skill.WOODCUTTING, 700L);
		session.addUncreditedXp(Skill.WOODCUTTING, 300L);
		session.addUncreditedXp(Skill.MINING, 450L);

		long wcRemainder = session.uncreditedXpFor(Skill.WOODCUTTING);
		long chunks = wcRemainder / XpCreditMath.XP_PER_CREDIT_CHUNK;
		assertEquals(1L, chunks);

		session.subtractUncreditedXp(Skill.WOODCUTTING, chunks * XpCreditMath.XP_PER_CREDIT_CHUNK);

		assertEquals(0L, session.uncreditedXpFor(Skill.WOODCUTTING));
		assertEquals(450L, session.uncreditedXpFor(Skill.MINING));
	}

	@Test
	public void multiChunkRemainderStaysInSameSkillBucket()
	{
		SkillCreditSession session = new SkillCreditSession();
		session.addUncreditedXp(Skill.MINING, 2500L);

		long remainder = session.uncreditedXpFor(Skill.MINING);
		long chunks = remainder / XpCreditMath.XP_PER_CREDIT_CHUNK;
		assertEquals(2L, chunks);

		long xpCredited = chunks * XpCreditMath.XP_PER_CREDIT_CHUNK;
		session.subtractUncreditedXp(Skill.MINING, xpCredited);

		assertEquals(500L, session.uncreditedXpFor(Skill.MINING));
		assertEquals(XpCreditMath.CREDITS_PER_CHUNK * 2L, XpCreditMath.creditsFromXpChunks(chunks));
	}

	@Test
	public void restoreLegacyBaselineWithOnlyGlobalRemainderIsEmpty()
	{
		SkillCreditSession session = new SkillCreditSession();
		Map<String, Integer> skillXp = new LinkedHashMap<>();
		skillXp.put("Woodcutting", 100_000);
		SkillCreditBaseline legacy = SkillCreditBaseline.of(skillXp, Map.of());

		session.restoreUncreditedXp(legacy);

		assertEquals(0L, session.totalUncreditedXp());
		assertEquals(0L, session.uncreditedXpFor(Skill.WOODCUTTING));
	}

	@Test
	public void roundTripPersistRestorePerSkillRemainders()
	{
		SkillCreditSession session = new SkillCreditSession();
		session.skillXpInitialized = true;
		Arrays.fill(session.previousSkillXp, 5000);
		session.addUncreditedXp(Skill.WOODCUTTING, 450L);
		session.addUncreditedXp(Skill.FISHING, 820L);

		SkillCreditBaseline baseline = session.toBaseline();
		assertTrue(baseline.isPresent());
		assertEquals(450L, baseline.uncreditedXpFor(Skill.WOODCUTTING).orElse(-1L));
		assertEquals(820L, baseline.uncreditedXpFor(Skill.FISHING).orElse(-1L));

		SkillCreditSession restored = new SkillCreditSession();
		restored.restoreUncreditedXp(baseline);

		assertEquals(450L, restored.uncreditedXpFor(Skill.WOODCUTTING));
		assertEquals(820L, restored.uncreditedXpFor(Skill.FISHING));
		assertEquals(0L, restored.uncreditedXpFor(Skill.MINING));
	}

	@Test
	public void clearUncreditedXpPoolResetsAllSkillBuckets()
	{
		SkillCreditSession session = new SkillCreditSession();
		session.addUncreditedXp(Skill.WOODCUTTING, 100L);
		session.addUncreditedXp(Skill.MINING, 200L);
		session.pendingSlayerXpToAttest = 50L;
		session.slayerXpRemainder = 75L;

		session.clearUncreditedXpPool();

		assertEquals(0L, session.totalUncreditedXp());
		assertEquals(0L, session.pendingSlayerXpToAttest);
		assertEquals(0L, session.slayerXpRemainder);
	}

	@Test
	public void activityXpPoolsAccumulatePerActivity()
	{
		SkillCreditSession session = new SkillCreditSession();

		assertEquals(0L, session.activityXpFor("mta_magic"));
		assertEquals(400L, session.addActivityXp("mta_magic", 400L));
		assertEquals(900L, session.addActivityXp("mta_magic", 500L));
		assertEquals(250L, session.addActivityXp("other_rule", 250L));

		assertEquals(900L, session.activityXpFor("mta_magic"));
		assertEquals(250L, session.activityXpFor("other_rule"));
	}

	@Test
	public void activityXpChunkSubtractionKeepsRemainder()
	{
		SkillCreditSession session = new SkillCreditSession();
		long pooled = session.addActivityXp("mta_magic", 2350L);

		long chunks = pooled / 1000L;
		assertEquals(2L, chunks);
		session.subtractActivityXp("mta_magic", chunks * 1000L);

		assertEquals(350L, session.activityXpFor("mta_magic"));
	}

	@Test
	public void activityXpIgnoresInvalidInput()
	{
		SkillCreditSession session = new SkillCreditSession();

		assertEquals(0L, session.addActivityXp(null, 100L));
		assertEquals(0L, session.addActivityXp("", 100L));
		assertEquals(0L, session.addActivityXp("mta_magic", 0L));
		assertEquals(0L, session.addActivityXp("mta_magic", -5L));
		assertEquals(0L, session.activityXpFor(null));

		session.addActivityXp("mta_magic", 100L);
		session.subtractActivityXp("mta_magic", 500L);
		assertEquals(0L, session.activityXpFor("mta_magic"));
		assertFalse(session.uncreditedXpByActivity.containsKey("mta_magic"));
	}

	@Test
	public void clearAndRestoreDropActivityXpPools()
	{
		SkillCreditSession session = new SkillCreditSession();
		session.addActivityXp("mta_magic", 600L);
		session.clearUncreditedXpPool();
		assertEquals(0L, session.activityXpFor("mta_magic"));

		session.addActivityXp("mta_magic", 600L);
		session.restoreUncreditedXp(SkillCreditBaseline.of(Map.of("Woodcutting", 1000), Map.of("Woodcutting", 10L)));
		assertEquals(0L, session.activityXpFor("mta_magic"));
		assertEquals(10L, session.uncreditedXpFor(Skill.WOODCUTTING));
	}
}

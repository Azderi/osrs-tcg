package com.osrstcg.credit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CreditAwardServiceTest
{
	@Test
	public void hopFromRestrictedWorldUsesLongerSettle()
	{
		assertEquals(
			CreditAwardService.RESTRICTED_WORLD_EXIT_SETTLE_TICKS,
			CreditAwardService.resolveHopSettleCooldownTicks(true));
	}

	@Test
	public void normalHopKeepsDefaultSettle()
	{
		assertEquals(
			CreditAwardService.CREDIT_COOLDOWN_TICKS,
			CreditAwardService.resolveHopSettleCooldownTicks(false));
	}

	@Test
	public void leavingRestrictedArmsHoldOnHop()
	{
		assertTrue(CreditAwardService.armRestrictedHoldOnHop(true));
	}

	@Test
	public void normalHopDoesNotArmRestrictedHold()
	{
		assertFalse(CreditAwardService.armRestrictedHoldOnHop(false));
	}
}

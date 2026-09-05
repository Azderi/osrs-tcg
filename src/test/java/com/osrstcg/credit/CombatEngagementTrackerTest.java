package com.osrstcg.credit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CombatEngagementTrackerTest
{
	@Test
	public void noCombatSeenIsNeverLockedOut()
	{
		assertFalse(CombatEngagementTracker.withinLockout(false, 0, 0, 8));
		assertFalse(CombatEngagementTracker.withinLockout(false, 100, 100, 8));
	}

	@Test
	public void sameTickAsCombatIsLockedOut()
	{
		assertTrue(CombatEngagementTracker.withinLockout(true, 100, 100, 8));
	}

	@Test
	public void lockoutIsInclusiveOfLastTick()
	{
		assertTrue(CombatEngagementTracker.withinLockout(true, 100, 108, 8));
		assertFalse(CombatEngagementTracker.withinLockout(true, 100, 109, 8));
	}

	@Test
	public void zeroLockoutOnlyCoversTheCombatTick()
	{
		assertTrue(CombatEngagementTracker.withinLockout(true, 100, 100, 0));
		assertFalse(CombatEngagementTracker.withinLockout(true, 100, 101, 0));
	}

	@Test
	public void negativeLockoutBehavesLikeZero()
	{
		assertTrue(CombatEngagementTracker.withinLockout(true, 100, 100, -5));
		assertFalse(CombatEngagementTracker.withinLockout(true, 100, 101, -5));
	}

	@Test
	public void tickCounterGoingBackwardsIsNotCombat()
	{
		assertFalse(CombatEngagementTracker.withinLockout(true, 100, 50, 8));
	}

	@Test
	public void largeTickValuesDoNotOverflow()
	{
		assertTrue(CombatEngagementTracker.withinLockout(true, Integer.MAX_VALUE - 2, Integer.MAX_VALUE, 8));
		assertFalse(CombatEngagementTracker.withinLockout(true, Integer.MAX_VALUE, Integer.MIN_VALUE, 8));
	}
}

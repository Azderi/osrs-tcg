package com.runelitetcg.util;

import org.junit.Assert;
import org.junit.Test;

public class PlayerCombatUtilTest
{
	@Test
	public void nullClientIsNotInCombat()
	{
		Assert.assertFalse(PlayerCombatUtil.isLocalPlayerInCombat(null));
	}
}

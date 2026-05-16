package com.osrstcg.service;

import org.junit.Assert;
import org.junit.Test;

public class RarityMathLinearTierPullTest
{
	@Test
	public void linearTierPullWeight_endpointsAndMidpoint()
	{
		Assert.assertEquals(1.0d, RarityMath.linearTierPullWeightByScore(0.0d, 0.0d, 100.0d, 3.0d), 1e-12);
		Assert.assertEquals(1.0d / 3.0d, RarityMath.linearTierPullWeightByScore(100.0d, 0.0d, 100.0d, 3.0d), 1e-12);
		Assert.assertEquals(2.0d / 3.0d, RarityMath.linearTierPullWeightByScore(50.0d, 0.0d, 100.0d, 3.0d), 1e-12);
	}

	@Test
	public void linearTierPullWeight_equalMinMaxReturnsOne()
	{
		Assert.assertEquals(1.0d, RarityMath.linearTierPullWeightByScore(42.0d, 10.0d, 10.0d, 3.0d), 1e-12);
	}

	@Test
	public void linearTierPullWeight_clampsRatioBelowOne()
	{
		Assert.assertEquals(1.0d, RarityMath.linearTierPullWeightByScore(100.0d, 0.0d, 100.0d, 0.5d), 1e-12);
	}
}

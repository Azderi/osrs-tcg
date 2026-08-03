package com.osrstcg.service;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DinkNotificationServiceTest
{
	@Test
	public void packSummaryIsSuppressedWhenNoPullIsNotificationEligible()
	{
		assertFalse(DinkNotificationService.hasNotificationEligiblePull(Arrays.asList(
			pull("Common card", false),
			pull("Rare card", false))));
	}

	@Test
	public void packSummaryIsAllowedWhenAnyPullIsNotificationEligible()
	{
		assertTrue(DinkNotificationService.hasNotificationEligiblePull(Arrays.asList(
			pull("Common card", false),
			pull("Mythic card", true),
			pull("Another common card", false))));
	}

	@Test
	public void emptyOrNullPackSummaryIsSuppressed()
	{
		assertFalse(DinkNotificationService.hasNotificationEligiblePull(null));
		assertFalse(DinkNotificationService.hasNotificationEligiblePull(Collections.emptyList()));
		assertFalse(DinkNotificationService.hasNotificationEligiblePull(Collections.singletonList(null)));
	}

	private static DinkNotificationService.PackPull pull(String cardName, boolean notificationEligible)
	{
		return new DinkNotificationService.PackPull(
			cardName,
			true,
			false,
			RarityMath.Tier.COMMON,
			notificationEligible);
	}
}

package com.osrstcg.notify;

import com.osrstcg.catalog.RarityMath;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PullNotificationMessagesTest
{
	@Test
	public void packSummaryOmitsEmptyDuplicatesSection()
	{
		PullNotificationMessages.PackSummarySections sections = new PullNotificationMessages.PackSummarySections(
			Arrays.asList("**Zilyana**", "Goblin"),
			Collections.emptyList());
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**New cards**\n- **Zilyana**\n- Goblin",
			PullNotificationMessages.packSummaryMessage("%USERNAME%", sections));
	}

	@Test
	public void packSummaryOmitsEmptyNewCardsSection()
	{
		PullNotificationMessages.PackSummarySections sections = new PullNotificationMessages.PackSummarySections(
			Collections.emptyList(),
			Collections.singletonList("**General Graardor**"));
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**Duplicates**\n- **General Graardor**",
			PullNotificationMessages.packSummaryMessage("%USERNAME%", sections));
	}

	@Test
	public void packSummaryContainsOnlyOpeningLineWhenBothSectionsAreEmpty()
	{
		PullNotificationMessages.PackSummarySections sections = new PullNotificationMessages.PackSummarySections(
			Collections.emptyList(),
			Collections.emptyList());
		assertEquals(
			"%USERNAME% opened a booster pack!",
			PullNotificationMessages.packSummaryMessage("%USERNAME%", sections));
	}

	@Test
	public void packSummaryIsSuppressedWhenNoPullIsNotificationEligible()
	{
		assertFalse(PullNotificationMessages.hasEligiblePull(Arrays.asList(
			pull("Common card", false),
			pull("Rare card", false))));
	}

	@Test
	public void packSummaryIsAllowedWhenAnyPullIsNotificationEligible()
	{
		assertTrue(PullNotificationMessages.hasEligiblePull(Arrays.asList(
			pull("Common card", false),
			pull("Mythic card", true),
			pull("Another common card", false))));
	}

	@Test
	public void emptyOrNullPackSummaryIsSuppressed()
	{
		assertFalse(PullNotificationMessages.hasEligiblePull(null));
		assertFalse(PullNotificationMessages.hasEligiblePull(Collections.emptyList()));
		assertFalse(PullNotificationMessages.hasEligiblePull(Collections.singletonList(null)));
	}

	private static PullNotificationMessages.PackPull pull(String cardName, boolean notificationEligible)
	{
		return new PullNotificationMessages.PackPull(
			cardName,
			true,
			false,
			RarityMath.Tier.COMMON,
			null,
			notificationEligible);
	}
}

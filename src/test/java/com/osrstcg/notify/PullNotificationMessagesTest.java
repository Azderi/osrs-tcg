package com.osrstcg.notify;

import com.osrstcg.catalog.RarityMath;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PullNotificationMessagesTest
{
	@Test
	public void packSummaryOmitsEmptyDuplicatesSection()
	{
		List<PullNotificationMessages.PackPull> pulls = Arrays.asList(
			pull("Zilyana", true),
			pull("Goblin", false));
		PullNotificationMessages.PackSummarySections sections = PullNotificationMessages.buildSummarySections(pulls);
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**New cards**\n- **Zilyana**\n- Goblin",
			PullNotificationMessages.packSummaryMessage("%USERNAME%", sections));
	}

	@Test
	public void packSummaryOmitsEmptyNewCardsSection()
	{
		List<PullNotificationMessages.PackPull> pulls = Collections.singletonList(
			new PullNotificationMessages.PackPull(
				"General Graardor", false, false, RarityMath.Tier.COMMON, null, true));
		PullNotificationMessages.PackSummarySections sections = PullNotificationMessages.buildSummarySections(pulls);
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**Duplicates**\n- **General Graardor**",
			PullNotificationMessages.packSummaryMessage("%USERNAME%", sections));
	}

	@Test
	public void packSummaryContainsOnlyOpeningLineWhenBothSectionsAreEmpty()
	{
		PullNotificationMessages.PackSummarySections sections =
			PullNotificationMessages.buildSummarySections(Collections.emptyList());
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

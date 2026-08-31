package com.osrstcg.notify;

import com.osrstcg.catalog.RarityMath;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static com.osrstcg.notify.PullNotificationMessages.buildSummarySections;
import static com.osrstcg.notify.PullNotificationMessages.hasEligiblePull;
import static com.osrstcg.notify.PullNotificationMessages.packSummaryMessage;
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
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**New cards**\n- **Zilyana**\n- Goblin",
			packSummaryMessage("%USERNAME%", buildSummarySections(pulls)));
	}

	@Test
	public void packSummaryOmitsEmptyNewCardsSection()
	{
		List<PullNotificationMessages.PackPull> pulls = Collections.singletonList(
			new PullNotificationMessages.PackPull(
				"General Graardor", false, false, RarityMath.Tier.COMMON, null, true));
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**Duplicates**\n- **General Graardor**",
			packSummaryMessage("%USERNAME%", buildSummarySections(pulls)));
	}

	@Test
	public void packSummaryContainsOnlyOpeningLineWhenBothSectionsAreEmpty()
	{
		assertEquals(
			"%USERNAME% opened a booster pack!",
			packSummaryMessage("%USERNAME%", buildSummarySections(Collections.emptyList())));
	}

	@Test
	public void packSummaryIsSuppressedWhenNoPullIsNotificationEligible()
	{
		assertFalse(hasEligiblePull(Arrays.asList(
			pull("Common card", false),
			pull("Rare card", false))));
	}

	@Test
	public void packSummaryIsAllowedWhenAnyPullIsNotificationEligible()
	{
		assertTrue(hasEligiblePull(Arrays.asList(
			pull("Common card", false),
			pull("Mythic card", true),
			pull("Another common card", false))));
	}

	@Test
	public void emptyOrNullPackSummaryIsSuppressed()
	{
		assertFalse(hasEligiblePull(null));
		assertFalse(hasEligiblePull(Collections.emptyList()));
		assertFalse(hasEligiblePull(Collections.singletonList(null)));
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

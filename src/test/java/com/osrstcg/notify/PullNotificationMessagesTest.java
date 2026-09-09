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
	public void summaryLinksBoldTitleAndShowsGradeAndCondition()
	{
		PullNotificationMessages.PackPull pull = new PullNotificationMessages.PackPull(
			" Masori Chaps ", true, false, RarityMath.Tier.COMMON, " instance-123 ", true, 95.44);
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**New cards**\n- **[Masori Chaps]("
				+ PullNotificationMessages.inspectUrl("instance-123") + ")** - S (95.44)",
			packSummaryMessage("%USERNAME%", buildSummarySections(Collections.singletonList(pull), true)));
	}

	@Test
	public void summaryHidesGradeAndConditionWhilePreservingLinksFoilAndBoldTitles()
	{
		List<PullNotificationMessages.PackPull> pulls = Arrays.asList(
			new PullNotificationMessages.PackPull(
				"Masori Chaps", true, false, RarityMath.Tier.COMMON, "instance-123", true, 95.44),
			new PullNotificationMessages.PackPull(
				"Goblin", false, true, RarityMath.Tier.COMMON, "instance-456", false, 74.5));
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**New cards**\n- **[Masori Chaps]("
				+ PullNotificationMessages.inspectUrl("instance-123") + ")**"
				+ "\n\n**Duplicates**\n- [Goblin (foil)]("
				+ PullNotificationMessages.inspectUrl("instance-456") + ")",
			packSummaryMessage("%USERNAME%", buildSummarySections(pulls, false)));
	}

	@Test
	public void summaryPreservesFoilAndUnboldedIneligibleTitle()
	{
		PullNotificationMessages.PackPull pull = new PullNotificationMessages.PackPull(
			"Goblin", false, true, RarityMath.Tier.COMMON, "instance-456", false, 74.5);
		assertEquals(
			"[Goblin (foil)](" + PullNotificationMessages.inspectUrl("instance-456") + ") - B (74.50)",
			PullNotificationMessages.summaryLine(pull));
	}

	@Test
	public void summaryShowsConditionWithoutAnInspectLinkWhenInstanceIsMissing()
	{
		PullNotificationMessages.PackPull pull = new PullNotificationMessages.PackPull(
			"Goblin", true, false, RarityMath.Tier.COMMON, " ", true, 5.0);
		assertEquals("**Goblin** - D (5.00)", PullNotificationMessages.summaryLine(pull));
	}

	@Test
	public void summaryOmitsMissingOrInvalidCondition()
	{
		for (Double condition : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY))
		{
			PullNotificationMessages.PackPull pull = new PullNotificationMessages.PackPull(
				"Goblin", true, false, RarityMath.Tier.COMMON, "instance-789", true, condition);
			assertEquals("**[Goblin](" + PullNotificationMessages.inspectUrl("instance-789") + ")**",
				PullNotificationMessages.summaryLine(pull));
		}
	}

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

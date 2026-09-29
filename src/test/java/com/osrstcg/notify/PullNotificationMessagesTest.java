package com.osrstcg.notify;

import com.osrstcg.catalog.RarityMath;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static com.osrstcg.notify.PullNotificationMessages.buildSummarySections;
import static com.osrstcg.notify.PullNotificationMessages.hasEligiblePull;
import static com.osrstcg.notify.PullNotificationMessages.highestTierPull;
import static com.osrstcg.notify.PullNotificationMessages.packSummaryMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
			PullNotificationMessages.summaryLine(pull, true));
	}

	@Test
	public void summaryShowsConditionWithoutAnInspectLinkWhenInstanceIsMissing()
	{
		PullNotificationMessages.PackPull pull = new PullNotificationMessages.PackPull(
			"Goblin", true, false, RarityMath.Tier.COMMON, " ", true, 5.0);
		assertEquals("**Goblin** - D (5.00)", PullNotificationMessages.summaryLine(pull, true));
	}

	@Test
	public void summaryOmitsMissingOrInvalidCondition()
	{
		for (Double condition : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY))
		{
			PullNotificationMessages.PackPull pull = new PullNotificationMessages.PackPull(
				"Goblin", true, false, RarityMath.Tier.COMMON, "instance-789", true, condition);
			assertEquals("**[Goblin](" + PullNotificationMessages.inspectUrl("instance-789") + ")**",
				PullNotificationMessages.summaryLine(pull, true));
		}
	}

	@Test
	public void summaryOrdersFoilsThenRegularCardsByTierThenGradeWithinEachSection()
	{
		List<PullNotificationMessages.PackPull> pulls = Arrays.asList(
			pull("New regular mythic E", true, false, RarityMath.Tier.MYTHIC, 0.0),
			pull("Duplicate regular rare S", false, false, RarityMath.Tier.RARE, 99.0),
			pull("New foil common S", true, true, RarityMath.Tier.COMMON, 99.0),
			pull("Duplicate foil common S", false, true, RarityMath.Tier.COMMON, 99.0),
			pull("New regular common S", true, false, RarityMath.Tier.COMMON, 99.0),
			pull("Duplicate regular godly E", false, false, RarityMath.Tier.GODLY, 0.0),
			pull("New foil godly E", true, true, RarityMath.Tier.GODLY, 0.0),
			pull("Duplicate foil mythic E", false, true, RarityMath.Tier.MYTHIC, 0.0),
			pull("New foil godly A", true, true, RarityMath.Tier.GODLY, 80.0),
			pull("Duplicate foil mythic A", false, true, RarityMath.Tier.MYTHIC, 80.0));

		PullNotificationMessages.PackSummarySections sections = buildSummarySections(pulls, true);

		assertEquals(Arrays.asList(
			"New foil godly A (foil) - A (80.00)",
			"New foil godly E (foil) - E (0.00)",
			"New foil common S (foil) - S (99.00)",
			"New regular mythic E - E (0.00)",
			"New regular common S - S (99.00)"), sections.newCards);
		assertEquals(Arrays.asList(
			"Duplicate foil mythic A (foil) - A (80.00)",
			"Duplicate foil mythic E (foil) - E (0.00)",
			"Duplicate foil common S (foil) - S (99.00)",
			"Duplicate regular godly E - E (0.00)",
			"Duplicate regular rare S - S (99.00)"), sections.duplicates);
	}

	@Test
	public void summaryOrderingUsesConditionForGradeTiesAndKeepsMissingValuesStable()
	{
		List<PullNotificationMessages.PackPull> pulls = Arrays.asList(
			pull("First foil rare A", true, true, RarityMath.Tier.RARE, 80.0),
			pull("First regular untiered invalid", true, false, null, Double.NaN),
			pull("Second foil rare A", true, true, RarityMath.Tier.RARE, 90.0),
			pull("Regular untiered S", true, false, null, 99.0),
			pull("Second regular untiered invalid", true, false, null, null),
			pull("Third regular untiered invalid", true, false, null, Double.POSITIVE_INFINITY),
			pull("Foil untiered S", true, true, null, 99.0));

		assertEquals(Arrays.asList(
			"Second foil rare A (foil) - A (90.00)",
			"First foil rare A (foil) - A (80.00)",
			"Foil untiered S (foil) - S (99.00)",
			"Regular untiered S - S (99.00)",
			"First regular untiered invalid",
			"Second regular untiered invalid",
			"Third regular untiered invalid"), buildSummarySections(pulls, true).newCards);
	}

	@Test
	public void thumbnailPrefersHighestTierFoilOverHigherTierRegularCard()
	{
		PullNotificationMessages.PackPull regular = pull(
			"Godly regular", true, false, RarityMath.Tier.GODLY, 95.0, true);
		PullNotificationMessages.PackPull lowerFoil = pull(
			"Rare foil", true, true, RarityMath.Tier.RARE, 20.0, true);
		PullNotificationMessages.PackPull higherFoil = pull(
			"Legendary foil", true, true, RarityMath.Tier.LEGENDARY, 10.0, true);

		assertSame(higherFoil, highestTierPull(Arrays.asList(regular, lowerFoil, higherFoil)));
	}

	@Test
	public void thumbnailFallsBackToHighestTierRegularAndPreservesFirstTie()
	{
		PullNotificationMessages.PackPull firstMythic = pull(
			"First mythic", true, false, RarityMath.Tier.MYTHIC, 10.0, true);
		PullNotificationMessages.PackPull secondMythic = pull(
			"Second mythic", true, false, RarityMath.Tier.MYTHIC, 99.0, true);

		assertSame(firstMythic, highestTierPull(Arrays.asList(
			pull("Rare", true, false, RarityMath.Tier.RARE, 99.0, true), firstMythic, secondMythic)));
	}

	@Test
	public void thumbnailIgnoresHigherTierIneligiblePull()
	{
		PullNotificationMessages.PackPull godlyDuplicate = pull(
			"Godly duplicate", false, false, RarityMath.Tier.GODLY, 99.0, false);
		PullNotificationMessages.PackPull newMythic = pull(
			"New mythic", true, false, RarityMath.Tier.MYTHIC, 50.0, true);

		assertSame(newMythic, highestTierPull(Arrays.asList(godlyDuplicate, newMythic)));
	}

	@Test
	public void thumbnailIsNullWhenNoPullIsNotificationEligible()
	{
		assertNull(highestTierPull(Arrays.asList(
			pull("Godly duplicate", false, false, RarityMath.Tier.GODLY, 99.0, false),
			pull("Rare duplicate", false, true, RarityMath.Tier.RARE, 80.0, false))));
	}

	@Test
	public void thumbnailSelectionIsNullSafeAndKeepsUntieredFoilPreference()
	{
		PullNotificationMessages.PackPull untieredFoil = pull(
			"Untiered foil", true, true, null, null, true);
		assertNull(highestTierPull(null));
		assertNull(highestTierPull(Arrays.asList(null, null)));
		assertSame(untieredFoil, highestTierPull(Arrays.asList(
			null,
			pull("Godly regular", true, false, RarityMath.Tier.GODLY, 99.0, true),
			untieredFoil)));
	}

	@Test
	public void packSummaryOmitsEmptyDuplicatesSection()
	{
		List<PullNotificationMessages.PackPull> pulls = Arrays.asList(
			pull("Zilyana", true),
			pull("Goblin", false));
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**New cards**\n- **Zilyana**\n- Goblin",
			packSummaryMessage("%USERNAME%", buildSummarySections(pulls, true)));
	}

	@Test
	public void packSummaryOmitsEmptyNewCardsSection()
	{
		List<PullNotificationMessages.PackPull> pulls = Collections.singletonList(
			new PullNotificationMessages.PackPull(
				"General Graardor", false, false, RarityMath.Tier.COMMON, null, true, null));
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**Duplicates**\n- **General Graardor**",
			packSummaryMessage("%USERNAME%", buildSummarySections(pulls, true)));
	}

	@Test
	public void packSummaryContainsOnlyOpeningLineWhenBothSectionsAreEmpty()
	{
		assertEquals(
			"%USERNAME% opened a booster pack!",
			packSummaryMessage("%USERNAME%", buildSummarySections(Collections.emptyList(), true)));
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
			notificationEligible,
			null);
	}

	private static PullNotificationMessages.PackPull pull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, Double condition)
	{
		return pull(cardName, newForCollection, foil, tier, condition, false);
	}

	private static PullNotificationMessages.PackPull pull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, Double condition,
		boolean notificationEligible)
	{
		return new PullNotificationMessages.PackPull(
			cardName,
			newForCollection,
			foil,
			tier,
			null,
			notificationEligible,
			condition);
	}
}

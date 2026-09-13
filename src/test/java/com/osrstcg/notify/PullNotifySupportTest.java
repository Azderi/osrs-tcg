package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.notify.PullNotificationMessages.PackPull;
import com.osrstcg.notify.PullNotifySupport.PackSummaryContent;
import com.osrstcg.state.TcgState;
import com.osrstcg.state.TcgStateService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Covers {@link PullNotifySupport#packSummaryContent} building structured per-card metadata for Dink. */
public class PullNotifySupportTest
{
	private static final long WHITE_BERET_PULLED_AT = 1757683200000L;

	@Test
	public void newCardDetailIncludesCatalogAndPullMetadata()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());
		PackPull whiteBeret = new PackPull(
			"White beret", true, false, RarityMath.Tier.LEGENDARY, "instance-1", true,
			null, 8200L, WHITE_BERET_PULLED_AT);

		PackSummaryContent content = support.packSummaryContent(List.of(whiteBeret)).orElseThrow();

		assertEquals(1, content.newCardDetails.size());
		assertTrue(content.duplicateDetails.isEmpty());
		Map<String, Object> detail = content.newCardDetails.get(0);
		assertEquals("White beret", detail.get("cardName"));
		assertEquals(false, detail.get("foil"));
		assertEquals("Legendary", detail.get("rarityTier"));
		assertEquals(8200L, detail.get("score"));
		assertEquals("instance-1", detail.get("instanceId"));
		assertEquals(PullNotificationMessages.inspectUrl("instance-1"), detail.get("inspectUrl"));
		assertEquals("https://osrs-tcg.net/images/cards/white_beret.webp", detail.get("imageUrl"));
		assertEquals(List.of("Clothing", "Quest reward"), detail.get("category"));
		assertEquals(List.of("Kandarin"), detail.get("regions"));
		assertEquals(Instant.ofEpochMilli(WHITE_BERET_PULLED_AT).toString(), detail.get("pulledAt"));
		assertFalse("no condition was pulled, so the key should be absent", detail.containsKey("condition"));
	}

	@Test
	public void duplicateDetailIncludesConditionWhenGradingIsEnabled()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());
		PackPull dragonfruitPie = new PackPull(
			"Dragonfruit pie", false, false, RarityMath.Tier.COMMON, "instance-2", false,
			87.4, 300L, null);

		PackSummaryContent content = support.packSummaryContent(
			List.of(dragonfruitPie, eligibleThrowawayPull())).orElseThrow();

		assertEquals(1, content.duplicateDetails.size());
		Map<String, Object> detail = content.duplicateDetails.get(0);
		assertEquals("Dragonfruit pie", detail.get("cardName"));
		assertEquals(300L, detail.get("score"));
		assertEquals("Cooking", ((List<?>) detail.get("category")).get(0));
		assertEquals(87.4, (Double) detail.get("condition"), 0.0001);
		assertFalse("no pull timestamp was supplied, so the key should be absent", detail.containsKey("pulledAt"));
	}

	@Test
	public void conditionIsOmittedWhenGradeDisplayIsDisabled()
	{
		PullNotifySupport support = newSupport(false, whiteBeretDefinition(), dragonfruitPieDefinition());
		PackPull dragonfruitPie = new PackPull(
			"Dragonfruit pie", false, false, RarityMath.Tier.COMMON, "instance-2", false,
			87.4, 300L, null);

		PackSummaryContent content = support.packSummaryContent(
			List.of(dragonfruitPie, eligibleThrowawayPull())).orElseThrow();

		Map<String, Object> detail = content.duplicateDetails.get(0);
		assertFalse(detail.containsKey("condition"));
	}

	@Test
	public void unknownCardGetsEmptyCategoryAndRegionsAndNoLinksOrImage()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());
		PackPull unknownCard = new PackPull(
			"Unknown card", true, false, RarityMath.Tier.COMMON, null, true, null, 0L, null);

		PackSummaryContent content = support.packSummaryContent(List.of(unknownCard)).orElseThrow();

		Map<String, Object> detail = content.newCardDetails.get(0);
		assertEquals(List.of(), detail.get("category"));
		assertEquals(List.of(), detail.get("regions"));
		assertFalse(detail.containsKey("instanceId"));
		assertFalse(detail.containsKey("inspectUrl"));
		assertFalse(detail.containsKey("imageUrl"));
	}

	@Test
	public void detailsAreOrderedHighestTierFirstAndSurviveCatalogMutation()
	{
		CardDefinition whiteBeret = whiteBeretDefinition();
		PullNotifySupport support = newSupport(true, whiteBeret, dragonfruitPieDefinition());
		PackPull whiteBeretPull = new PackPull(
			"White beret", true, false, RarityMath.Tier.LEGENDARY, "instance-1", true, null, 8200L, null);
		PackPull unknownCommon = new PackPull(
			"Unknown card", true, false, RarityMath.Tier.COMMON, null, false, null, 0L, null);

		PackSummaryContent content = support.packSummaryContent(List.of(unknownCommon, whiteBeretPull)).orElseThrow();

		assertEquals(2, content.newCardDetails.size());
		assertEquals("White beret", content.newCardDetails.get(0).get("cardName"));
		assertEquals("Unknown card", content.newCardDetails.get(1).get("cardName"));

		// Mutating the catalog's live category list afterward must not retroactively change the
		// already-built detail map (it should hold a defensive copy, not a live reference).
		whiteBeret.getCategory().add("Mutated");
		assertEquals(List.of("Clothing", "Quest reward"), content.newCardDetails.get(0).get("category"));
	}

	@Test
	public void emptyWhenNoPullIsNotificationEligible()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());
		PackPull ineligible = new PackPull(
			"Dragonfruit pie", false, false, RarityMath.Tier.COMMON, "instance-2", false, null, 300L, null);

		Optional<PackSummaryContent> content = support.packSummaryContent(List.of(ineligible));

		assertTrue(content.isEmpty());
	}

	@Test
	public void pullCardContentIncludesCategoryAndRegionsFromCatalogInOneLookup()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());

		PullNotifySupport.PullCardContent content = support.pullCardContent(
			"White beret", true, false, "instance-1", "%USERNAME%", null);

		assertEquals(List.of("Clothing", "Quest reward"), content.category);
		assertEquals(List.of("Kandarin"), content.regions);
		assertEquals("https://osrs-tcg.net/images/cards/white_beret.webp", content.imageUrl);
	}

	@Test
	public void pullCardContentGivesUnknownCardEmptyCategoryAndRegions()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());

		PullNotifySupport.PullCardContent content = support.pullCardContent(
			"Unknown card", true, false, null, "%USERNAME%", null);

		assertEquals(List.of(), content.category);
		assertEquals(List.of(), content.regions);
		assertEquals("", content.imageUrl);
	}

	@Test
	public void pullCardContentIncludesConditionWhenGradingIsEnabled()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());

		PullNotifySupport.PullCardContent content = support.pullCardContent(
			"Dragonfruit pie", false, false, "instance-2", "%USERNAME%", 87.4);

		assertEquals(87.4, content.condition, 0.0001);
	}

	@Test
	public void pullCardContentOmitsConditionWhenGradingIsDisabled()
	{
		PullNotifySupport support = newSupport(false, whiteBeretDefinition(), dragonfruitPieDefinition());

		PullNotifySupport.PullCardContent content = support.pullCardContent(
			"Dragonfruit pie", false, false, "instance-2", "%USERNAME%", 87.4);

		assertEquals(null, content.condition);
	}

	@Test
	public void pullCardContentOmitsInvalidCondition()
	{
		PullNotifySupport support = newSupport(true, whiteBeretDefinition(), dragonfruitPieDefinition());

		PullNotifySupport.PullCardContent content = support.pullCardContent(
			"Dragonfruit pie", false, false, "instance-2", "%USERNAME%", Double.NaN);

		assertEquals(null, content.condition);
	}

	/** One notification-eligible filler pull so {@code hasEligiblePull} passes without affecting the assertions. */
	private static PackPull eligibleThrowawayPull()
	{
		return new PackPull(
			"Filler", true, false, RarityMath.Tier.MYTHIC, null, true, null, 0L, null);
	}

	private static CardDefinition whiteBeretDefinition()
	{
		CardDefinition def = new CardDefinition();
		def.setName("White beret");
		def.setCategory(new ArrayList<>(List.of("Clothing", "Quest reward")));
		def.setRegions(new ArrayList<>(List.of("Kandarin")));
		def.setImageUrl("https://osrs-tcg.net/images/cards/white_beret.png");
		return def;
	}

	private static CardDefinition dragonfruitPieDefinition()
	{
		CardDefinition def = new CardDefinition();
		def.setName("Dragonfruit pie");
		def.setCategory(new ArrayList<>(List.of("Cooking", "Food")));
		def.setRegions(new ArrayList<>(List.of("Tirannwn")));
		return def;
	}

	static PullNotifySupport newSupport(boolean showGradeAndCondition, CardDefinition... catalog)
	{
		CardDatabase cardDatabase = new CardDatabase();
		cardDatabase.replaceCards(List.of(catalog), "test");
		OsrsTcgConfig config = new OsrsTcgConfig()
		{
			@Override
			public boolean showPullGradeAndCondition()
			{
				return showGradeAndCondition;
			}
		};
		TcgPublicStatsCalculator statsCalculator = new TcgPublicStatsCalculator(
			new TcgStateService(TcgState.empty()), cardDatabase);
		return new PullNotifySupport(config, cardDatabase, statsCalculator, new TcgChatStatsShareService());
	}
}

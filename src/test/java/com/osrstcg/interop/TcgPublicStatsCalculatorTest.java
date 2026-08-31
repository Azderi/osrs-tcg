package com.osrstcg.interop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.PackCardResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TcgPublicStatsCalculatorTest
{
	@Test
	public void localOverviewCountsRollPoolUniquesAndScore()
	{
		CardDefinition goblin = card("Goblin", 10L, 20L);
		CardDefinition dragon = card("Dragon", 40L, 80L);
		Map<CardCollectionKey, Integer> owned = new HashMap<>();
		owned.put(new CardCollectionKey("Goblin", false), 2);

		CloudSidebarCollectionStats stats = TcgPublicStatsCalculator.computeLocalOverview(
			owned, List.of(goblin, dragon), List.of(goblin, dragon));

		assertEquals(1, stats.getUniqueOwned());
		assertEquals(0, stats.getUniqueFoilOwned());
		assertEquals(2, stats.getTotalCardsOwned());
		assertEquals(0L, stats.getFoilOwned());
		assertEquals(2, stats.getTotalCardPool());
		assertEquals(10L, stats.getCollectionScore());
		assertEquals(50.0d, stats.getCompletionPct(), 0.0001d);
	}

	@Test
	public void localOverviewUsesFoilScoreWhenAFoilCopyIsOwned()
	{
		CardDefinition goblin = card("Goblin", 10L, 20L);
		Map<CardCollectionKey, Integer> owned = new HashMap<>();
		owned.put(new CardCollectionKey("Goblin", false), 1);
		owned.put(new CardCollectionKey("Goblin", true), 1);

		CloudSidebarCollectionStats stats = TcgPublicStatsCalculator.computeLocalOverview(
			owned, List.of(goblin), List.of(goblin));

		assertEquals(1, stats.getUniqueOwned());
		assertEquals(1, stats.getUniqueFoilOwned());
		assertEquals(2, stats.getTotalCardsOwned());
		assertEquals(1L, stats.getFoilOwned());
		assertEquals(20L, stats.getCollectionScore());
	}

	@Test
	public void resolveOverviewPrefersSnapshotCloudStats()
	{
		CloudSidebarCollectionStats cloud = new CloudSidebarCollectionStats(
			5, 2, 9, 3L, 10, 50.0d, 20.0d, 100L);
		com.osrstcg.ui.layout.PackCloseSnapshot snap = new com.osrstcg.ui.layout.PackCloseSnapshot(
			Map.of(), null, 0L, 0L, cloud);

		CloudSidebarCollectionStats resolved = TcgPublicStatsCalculator.resolveOverview(
			snap, List.of(), List.of());
		assertEquals(cloud, resolved);
	}

	@Test
	public void optimisticPackPullIncrementsUniqueAndScoreForNewName()
	{
		CloudSidebarCollectionStats base = new CloudSidebarCollectionStats(
			1, 0, 2, 0L, 10, 10.0d, 0.0d, 10L);
		Map<CardCollectionKey, Integer> ownedBefore = new HashMap<>();
		ownedBefore.put(new CardCollectionKey("Goblin", false), 2);
		PackCardResult pull = new PackCardResult(
			"Dragon", true, "id-1", "Rare", 50L, null, null, null, null, null, null, null,
			null, null, null, null);

		CloudSidebarCollectionStats next = CloudSidebarCollectionStats.withOptimisticPackPulls(
			base, ownedBefore, List.of(pull));

		assertEquals(2, next.getUniqueOwned());
		assertEquals(1, next.getUniqueFoilOwned());
		assertEquals(3, next.getTotalCardsOwned());
		assertEquals(1L, next.getFoilOwned());
		assertEquals(60L, next.getCollectionScore());
		assertTrue(next.getCompletionPct() > base.getCompletionPct());
	}

	@Test
	public void countsAgreeIgnoresScoreDrift()
	{
		CloudSidebarCollectionStats server = new CloudSidebarCollectionStats(
			2, 1, 4, 1L, 10, 20.0d, 10.0d, 99L);
		CloudSidebarCollectionStats local = new CloudSidebarCollectionStats(
			2, 1, 4, 1L, 10, 20.01d, 10.0d, 100L);
		assertTrue(CloudSidebarCollectionStats.countsAgree(server, local));
	}

	private static CardDefinition card(String name, long score, long foilScore)
	{
		CardDefinition def = new CardDefinition();
		def.setName(name);
		def.setScore(score);
		def.setFoilScore(foilScore);
		return def;
	}
}

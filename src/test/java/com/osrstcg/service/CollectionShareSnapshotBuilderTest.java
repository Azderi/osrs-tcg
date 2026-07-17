package com.osrstcg.service;

import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.TcgPublicStats;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class CollectionShareSnapshotBuilderTest
{
	@Test
	public void aggregateCardsExcludesDebugAndGroupsByNameAndFoil()
	{
		List<OwnedCardInstance> instances = List.of(
			OwnedCardInstance.createNew("Abyssal whip", false, "Player", 1000L),
			OwnedCardInstance.createNew("Abyssal whip", false, "Player", 1001L),
			OwnedCardInstance.createNew("Abyssal whip", true, "Player", 1002L),
			OwnedCardInstance.createNew("Dragon scimitar", false,
				OwnedCardInstance.withDebugPullMetadataPrefix("Player"), 1003L),
			OwnedCardInstance.createNew("Twisted bow", false, "DEBUG_Other", 1004L)
		);

		List<Map<String, Object>> cards = CollectionShareSnapshotBuilder.aggregateCards(
			CollectionState.copyOf(instances));

		Assert.assertEquals(2, cards.size());

		Map<String, Object> whip = cards.get(0);
		Assert.assertEquals("Abyssal whip", whip.get("cardName"));
		Assert.assertEquals(false, whip.get("foil"));
		Assert.assertEquals(2, whip.get("quantity"));

		Map<String, Object> whipFoil = cards.get(1);
		Assert.assertEquals("Abyssal whip", whipFoil.get("cardName"));
		Assert.assertEquals(true, whipFoil.get("foil"));
		Assert.assertEquals(1, whipFoil.get("quantity"));
	}

	@Test
	public void buildInstancesExcludesDebugAndMapsPullFields()
	{
		OwnedCardInstance whip = OwnedCardInstance.createNew("Abyssal whip", false, "Player", 1000L);
		OwnedCardInstance foil = OwnedCardInstance.createNew("Abyssal whip", true, "Player", 1002L);
		OwnedCardInstance debug = OwnedCardInstance.createNew("Dragon scimitar", false,
			OwnedCardInstance.withDebugPullMetadataPrefix("Player"), 1003L);
		OwnedCardInstance emptyPuller = OwnedCardInstance.createNew("Rune scimitar", false, "", 50L);

		List<Map<String, Object>> rows = CollectionShareSnapshotBuilder.buildInstances(
			CollectionState.copyOf(List.of(whip, foil, debug, emptyPuller)));

		Assert.assertEquals(3, rows.size());

		Map<String, Object> first = rows.get(0);
		Assert.assertEquals(whip.getInstanceId(), first.get("instanceId"));
		Assert.assertEquals("Abyssal whip", first.get("cardName"));
		Assert.assertEquals(false, first.get("foil"));
		Assert.assertEquals("Player", first.get("pulledBy"));
		Assert.assertEquals(1000L, first.get("pulledAt"));

		Map<String, Object> second = rows.get(1);
		Assert.assertEquals(true, second.get("foil"));
		Assert.assertEquals(1002L, second.get("pulledAt"));

		Map<String, Object> third = rows.get(2);
		Assert.assertEquals("Rune scimitar", third.get("cardName"));
		Assert.assertFalse(third.containsKey("pulledBy"));
		Assert.assertEquals(50L, third.get("pulledAt"));
	}

	@Test
	public void buildPayloadIncludesInstancesAndShareSafeFields()
	{
		OwnedCardInstance owned = OwnedCardInstance.createNew("Rune scimitar", false, "Player", 1L);
		CollectionState collection = CollectionState.copyOf(List.of(owned));
		TcgPublicStats stats = new TcgPublicStats(10L, 1.5d, 1, 0, 0.0d, 100, 3L, 1, false);

		Map<String, Object> payload = CollectionShareSnapshotBuilder.buildPayload(
			"1.0.0",
			"TestPlayer",
			stats,
			collection,
			Instant.parse("2026-07-17T00:00:00Z"));

		Assert.assertEquals(1, payload.get("schemaVersion"));
		Assert.assertEquals("1.0.0", payload.get("catalogVersion"));
		Assert.assertEquals("TestPlayer", payload.get("displayName"));
		Assert.assertEquals("2026-07-17T00:00:00Z", payload.get("updatedAt"));
		Assert.assertTrue(payload.get("stats") instanceof Map);
		Assert.assertTrue(payload.get("cards") instanceof List);
		Assert.assertEquals(1, ((List<?>) payload.get("cards")).size());
		Assert.assertTrue(payload.get("instances") instanceof List);
		Assert.assertEquals(1, ((List<?>) payload.get("instances")).size());

		@SuppressWarnings("unchecked")
		Map<String, Object> instance = (Map<String, Object>) ((List<?>) payload.get("instances")).get(0);
		Assert.assertEquals(owned.getInstanceId(), instance.get("instanceId"));
		Assert.assertEquals("Player", instance.get("pulledBy"));
		Assert.assertEquals(1L, instance.get("pulledAt"));

		@SuppressWarnings("unchecked")
		Map<String, Object> statsMap = (Map<String, Object>) payload.get("stats");
		Assert.assertEquals(10L, statsMap.get("collectionScore"));
		Assert.assertEquals(false, statsMap.get("customRates"));
		Assert.assertFalse(statsMap.containsKey("credits"));
	}

	@Test
	public void aggregateCardsReturnsEmptyForNullOrEmptyCollection()
	{
		Assert.assertTrue(CollectionShareSnapshotBuilder.aggregateCards(null).isEmpty());
		Assert.assertTrue(CollectionShareSnapshotBuilder.aggregateCards(CollectionState.empty()).isEmpty());
		Assert.assertTrue(CollectionShareSnapshotBuilder.buildInstances(null).isEmpty());
		Assert.assertTrue(CollectionShareSnapshotBuilder.buildInstances(CollectionState.empty()).isEmpty());
	}
}

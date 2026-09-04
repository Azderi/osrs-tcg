package com.osrstcg.ui.shop;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShopProgressTest
{
	@Test
	public void newlyCompletedCollectionsChecksEachBoosterEvenWhenCollectionKeysMatch()
	{
		CardDefinition cardA = card("Alpha", "cat-a");
		CardDefinition cardB = card("Beta", "cat-b");
		List<CardDefinition> cards = Arrays.asList(cardA, cardB);

		BoosterPackDefinition alreadyComplete = booster("pack-a", "SET1", "cat-a");
		BoosterPackDefinition newlyComplete = booster("pack-b", "SET1", "cat-b");

		Map<CardCollectionKey, Integer> before = new HashMap<>();
		before.put(new CardCollectionKey("Alpha", false), 1);

		Map<CardCollectionKey, Integer> after = new HashMap<>(before);
		after.put(new CardCollectionKey("Beta", false), 1);

		List<String> completed = ShopProgress.newlyCompletedCollections(
			before,
			after,
			cards,
			cards,
			Arrays.asList(alreadyComplete, newlyComplete));

		assertEquals(Collections.singletonList("SET1"), completed);
	}

	@Test
	public void newlyCompletedCollectionsDedupesSharedDisplayName()
	{
		CardDefinition card = card("Solo", "shared");
		List<CardDefinition> cards = Collections.singletonList(card);

		BoosterPackDefinition first = booster("pack-1", "SET1", "shared");
		BoosterPackDefinition second = booster("pack-2", "SET1", "shared");

		Map<CardCollectionKey, Integer> before = Collections.emptyMap();
		Map<CardCollectionKey, Integer> after = new HashMap<>();
		after.put(new CardCollectionKey("Solo", false), 1);

		List<String> completed = ShopProgress.newlyCompletedCollections(
			before,
			after,
			cards,
			cards,
			Arrays.asList(first, second));

		assertEquals(1, completed.size());
		assertEquals("SET1", completed.get(0));
	}

	@Test
	public void newlyCompletedCollectionsSkipsBlankCollectionKeys()
	{
		CardDefinition card = card("Solo", "shared");
		BoosterPackDefinition blankKey = new BoosterPackDefinition();
		blankKey.setCategory(Collections.singletonList("shared"));

		Map<CardCollectionKey, Integer> after = new HashMap<>();
		after.put(new CardCollectionKey("Solo", false), 1);

		assertTrue(ShopProgress.newlyCompletedCollections(
			Collections.emptyMap(),
			after,
			Collections.singletonList(card),
			Collections.singletonList(card),
			Collections.singletonList(blankKey)).isEmpty());
	}

	private static CardDefinition card(String name, String category)
	{
		CardDefinition card = new CardDefinition();
		card.setName(name);
		card.setCategory(Collections.singletonList(category));
		return card;
	}

	private static BoosterPackDefinition booster(String id, String collectionName, String category)
	{
		BoosterPackDefinition booster = new BoosterPackDefinition();
		booster.setId(id);
		booster.setCollectionName(collectionName);
		booster.setCategory(Collections.singletonList(category));
		return booster;
	}
}

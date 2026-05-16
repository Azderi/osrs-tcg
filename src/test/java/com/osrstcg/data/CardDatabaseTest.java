package com.osrstcg.data;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class CardDatabaseTest
{
	@Test
	public void loadShouldPopulateNonEmptyCardPool()
	{
		CardDatabase database = new CardDatabase(new Gson());
		database.load();

		Assert.assertTrue("Expected Card.json to provide cards", database.size() > 0);
		Assert.assertFalse("Expected at least one category", database.categoryCounts().isEmpty());
	}

	@Test
	public void categoryDistributionShouldContainKnownBuckets()
	{
		CardDatabase database = new CardDatabase(new Gson());
		database.load();

		Map<String, Long> categories = database.categoryCounts();
		boolean hasKnownCategory = categories.containsKey("Monster")
			|| categories.containsKey("Resource")
			|| categories.containsKey("Consumable")
			|| categories.containsKey("Weapon")
			|| categories.containsKey("Armour")
			|| categories.containsKey("Equipment");

		Assert.assertTrue("Expected known design categories in Card.json", hasKnownCategory);
	}

	@Test
	public void optionalFieldsShouldBeTolerated()
	{
		CardDatabase database = new CardDatabase(new Gson());
		database.load();

		boolean hasCardWithMissingOptionals = database.getCards().stream()
			.anyMatch(card -> card.getExamine() == null || card.getImageUrl() == null || card.getValue() == null || card.getLevel() == null);

		Assert.assertTrue("Expected some cards with missing optional fields", hasCardWithMissingOptionals);
	}
}

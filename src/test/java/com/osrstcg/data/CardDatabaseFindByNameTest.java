package com.osrstcg.data;

import com.google.gson.Gson;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins {@link CardDatabase#findByName(String)} semantics: case- and whitespace-insensitive
 * match, first card wins when Card.json contains duplicate names, blank or unknown names
 * resolve empty.
 */
public class CardDatabaseFindByNameTest
{
	@Test
	public void findsCardIgnoringCaseAndSurroundingWhitespace()
	{
		CardDatabase db = new CardDatabase(new Gson());
		db.load();
		CardDefinition last = db.getCards().get(db.getCards().size() - 1);

		Optional<CardDefinition> found = db.findByName("  " + last.getName().toUpperCase() + "  ");

		Assert.assertTrue(found.isPresent());
		Assert.assertSame(last, found.get());
	}

	@Test
	public void firstCardWinsWhenNamesAreDuplicated()
	{
		CardDefinition first = named("Abyssal whip");
		CardDefinition second = named("Abyssal whip");
		CardDatabase db = new CardDatabase(new Gson());
		db.setCardsForTesting(List.of(first, second));

		Optional<CardDefinition> found = db.findByName("abyssal whip");

		Assert.assertTrue(found.isPresent());
		Assert.assertSame(first, found.get());
	}

	@Test
	public void blankAndUnknownNamesResolveEmpty()
	{
		CardDatabase db = new CardDatabase(new Gson());
		db.load();

		Assert.assertFalse(db.findByName(null).isPresent());
		Assert.assertFalse(db.findByName("   ").isPresent());
		Assert.assertFalse(db.findByName("No such card exists").isPresent());
	}

	@Test
	public void findsCardsAddedViaSetCardsForTesting()
	{
		CardDefinition card = named("Dragon scimitar");
		CardDatabase db = new CardDatabase(new Gson());
		db.setCardsForTesting(List.of(card));

		Assert.assertSame(card, db.findByName("Dragon Scimitar").orElse(null));
	}

	private static CardDefinition named(String name)
	{
		CardDefinition card = new CardDefinition();
		card.setName(name);
		return card;
	}
}

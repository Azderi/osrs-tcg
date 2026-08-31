package com.osrstcg.cloud.catalog;

import com.google.gson.JsonParser;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.util.CardDisplayNames;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class LiveCardsCatalogParserDisplayNameTest
{
	@Test
	public void collidingNpcKeepsIdentityNameAndFriendlyDisplayName()
	{
		List<CardDefinition> cards = LiveCardsCatalogParser.parse(new JsonParser().parse("{"
			+ "\"items\":[{\"id\":391,\"name\":\"Manta ray\",\"tcg\":{\"score\":1,\"tierLabel\":\"Common\"}}],"
			+ "\"npcs\":[{\"id\":15220,\"name\":\"Manta ray\",\"tcg\":{\"score\":10,\"tierLabel\":\"Rare\"}}]"
			+ "}").getAsJsonObject());

		CardDefinition npc = cards.stream()
			.filter(c -> "npc:15220".equals(c.getName()))
			.findFirst()
			.orElse(null);
		Assert.assertNotNull(npc);
		Assert.assertEquals("npc:15220", npc.getName());
		Assert.assertEquals("Manta ray", npc.getDisplayName());
		Assert.assertEquals(Long.valueOf(15220L), npc.getId());
	}

	@Test
	public void titleForPullPrefersApiDisplayNameOverNpcIdentity()
	{
		CardDefinition catalog = new CardDefinition();
		catalog.setName("npc:6616");
		catalog.setDisplayName("Scorpia's offspring");

		PackCardResult pull = new PackCardResult(
			"npc:6616",
			false,
			"id-1",
			"Rare",
			100L,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			"Scorpia's offspring");

		Assert.assertEquals("Scorpia's offspring", CardDisplayNames.titleForPull(pull, catalog));
		Assert.assertEquals("Scorpia's offspring", CardDisplayNames.titleForDefinition(catalog, pull));
	}

	@Test
	public void titleFallsBackToCatalogDisplayWhenPullOmitsName()
	{
		CardDefinition catalog = new CardDefinition();
		catalog.setName("npc:6616");
		catalog.setDisplayName("Scorpia's offspring");

		PackCardResult pull = new PackCardResult("npc:6616", false);
		Assert.assertEquals("Scorpia's offspring", CardDisplayNames.titleForPull(pull, catalog));
	}
}

package com.osrstcg.cloud.catalog;

import com.google.gson.JsonParser;
import com.osrstcg.state.PackCardResult;
import org.junit.Assert;
import org.junit.Test;

public class PackPullParserTest
{
	@Test
	public void parseCard_readsCustomExamineWithNewlines()
	{
		PackCardResult pull = PackPullParser.parseCard(new JsonParser().parse("{"
			+ "\"cardName\":\"Abyssal whip\","
			+ "\"foil\":true,"
			+ "\"instanceId\":\"id-1\","
			+ "\"tierLabel\":\"Rare\","
			+ "\"score\":100,"
			+ "\"foilImagePath\":\"/api/v1/artwork/files/xyz?token=t\","
			+ "\"artistName\":\"Ada\","
			+ "\"artistColor\":\"#FFFFFF\","
			+ "\"artistUrl\":\"https://example.com\","
			+ "\"examine\":\"Custom line\\nSecond line\""
			+ "}").getAsJsonObject());

		Assert.assertNotNull(pull);
		Assert.assertEquals("Custom line\nSecond line", pull.getExamine());
		Assert.assertEquals("/api/v1/artwork/files/xyz?token=t", pull.getFoilImagePath());
		Assert.assertEquals("Ada", pull.getArtistName());
	}

	@Test
	public void parseCard_omitsBlankExamine()
	{
		PackCardResult pull = PackPullParser.parseCard(new JsonParser().parse("{"
			+ "\"cardName\":\"Abyssal whip\","
			+ "\"foil\":true,"
			+ "\"instanceId\":\"id-2\","
			+ "\"foilImagePath\":\"/api/v1/artwork/files/xyz\","
			+ "\"examine\":\"   \""
			+ "}").getAsJsonObject());

		Assert.assertNotNull(pull);
		Assert.assertNull(pull.getExamine());
	}

	@Test
	public void parseCard_nonFoilHasNoExamineField()
	{
		PackCardResult pull = PackPullParser.parseCard(new JsonParser().parse("{"
			+ "\"cardName\":\"Abyssal whip\","
			+ "\"foil\":false,"
			+ "\"instanceId\":\"id-3\""
			+ "}").getAsJsonObject());

		Assert.assertNotNull(pull);
		Assert.assertNull(pull.getExamine());
		Assert.assertNull(pull.getFoilImagePath());
	}

	@Test
	public void parseCard_readsDisplayNameSeparateFromCardName()
	{
		PackCardResult pull = PackPullParser.parseCard(new JsonParser().parse("{"
			+ "\"cardName\":\"npc:6616\","
			+ "\"name\":\"Scorpia's offspring\","
			+ "\"foil\":false,"
			+ "\"instanceId\":\"id-npc\","
			+ "\"tierLabel\":\"Rare\","
			+ "\"score\":100"
			+ "}").getAsJsonObject());

		Assert.assertNotNull(pull);
		Assert.assertEquals("npc:6616", pull.getCardName());
		Assert.assertEquals("Scorpia's offspring", pull.getDisplayName());
	}

	@Test
	public void parseCard_omitsBlankDisplayName()
	{
		PackCardResult pull = PackPullParser.parseCard(new JsonParser().parse("{"
			+ "\"cardName\":\"Abyssal whip\","
			+ "\"name\":\"   \","
			+ "\"foil\":false,"
			+ "\"instanceId\":\"id-4\""
			+ "}").getAsJsonObject());

		Assert.assertNotNull(pull);
		Assert.assertEquals("Abyssal whip", pull.getCardName());
		Assert.assertNull(pull.getDisplayName());
	}
}

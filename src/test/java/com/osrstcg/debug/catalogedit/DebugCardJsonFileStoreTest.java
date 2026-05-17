package com.osrstcg.debug.catalogedit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class DebugCardJsonFileStoreTest
{
	@Test
	public void updateShouldPreserveUnknownJsonFields() throws Exception
	{
		Path file = Files.createTempFile("card-json-test-", ".json");
		try
		{
			String seed = "[{\"name\":\"Test sword\",\"value\":1,\"combatStats\":{\"attackStab\":5},\"category\":[\"Weapon\"]}]";
			Files.writeString(file, seed, StandardCharsets.UTF_8);

			DebugCardJsonFileStore store = new DebugCardJsonFileStore(new DebugCardJsonPaths());
			store.updateCard(file, "Test sword", new DebugCardJsonFileStore.CardJsonEdit(
				List.of("Weapon", "Resource"),
				"https://example.com/img.png",
				"Sharp.",
				99L,
				10,
				null,
				false));

			JsonArray array = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
			Assert.assertEquals(1, array.size());
			JsonObject card = array.get(0).getAsJsonObject();
			Assert.assertEquals(99, card.get("value").getAsLong());
			Assert.assertTrue(card.has("combatStats"));
			Assert.assertEquals(5, card.getAsJsonObject("combatStats").get("attackStab").getAsInt());
			Assert.assertEquals(2, card.getAsJsonArray("category").size());
		}
		finally
		{
			Files.deleteIfExists(file);
		}
	}

	@Test
	public void deleteShouldRemoveCardEntry() throws Exception
	{
		Path file = Files.createTempFile("card-json-test-", ".json");
		try
		{
			Files.writeString(file,
				"[{\"name\":\"Keep\"},{\"name\":\"Remove me\"}]",
				StandardCharsets.UTF_8);

			DebugCardJsonFileStore store = new DebugCardJsonFileStore(new DebugCardJsonPaths());
			store.deleteCard(file, "Remove me");

			JsonArray array = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
			Assert.assertEquals(1, array.size());
			Assert.assertEquals("Keep", array.get(0).getAsJsonObject().get("name").getAsString());
		}
		finally
		{
			Files.deleteIfExists(file);
		}
	}
}

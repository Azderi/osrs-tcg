package com.osrstcg.cloud.catalog;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.state.PackCardResult;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/** Parses {@code POST /packs/open} {@code cards[]} elements into {@link PackCardResult}. */
@Slf4j
public final class PackPullParser
{
	private PackPullParser()
	{
	}

	public static PackCardResult parseCard(JsonObject c)
	{
		if (c == null)
		{
			return null;
		}
		String name = JsonObjects.text(c, "cardName");
		if (name == null || name.isBlank())
		{
			return null;
		}
		String displayName = JsonObjects.text(c, "name");
		boolean foil = c.has("foil") && !c.get("foil").isJsonNull() && c.get("foil").getAsBoolean();
		String instanceId = JsonObjects.text(c, "instanceId");
		if (instanceId == null || instanceId.isBlank())
		{
			instanceId = UUID.randomUUID().toString();
		}
		String tierLabel = JsonObjects.text(c, "tierLabel");
		long score = 0L;
		if (c.has("score") && !c.get("score").isJsonNull())
		{
			try
			{
				score = Math.max(0L, Math.round(c.get("score").getAsDouble()));
			}
			catch (RuntimeException ex)
			{
				log.debug("Invalid pack pull score for {}", name, ex);
			}
		}
		String imagePath = JsonObjects.text(c, "imagePath");
		String foilImagePath = JsonObjects.text(c, "foilImagePath");
		String artistName = JsonObjects.text(c, "artistName");
		String artistColor = JsonObjects.text(c, "artistColor");
		String artistUrl = JsonObjects.text(c, "artistUrl");
		String examine = JsonObjects.text(c, "examine");
		String wikiPage = JsonObjects.text(c, "wikiPage");
		Double condition = null;
		if (c.has("condition") && !c.get("condition").isJsonNull())
		{
			try
			{
				condition = c.get("condition").getAsDouble();
			}
			catch (RuntimeException ignored)
			{
				condition = null;
			}
		}
		String pulledBy = JsonObjects.text(c, "pulledBy");
		Long pulledAt = null;
		if (c.has("pulledAt") && !c.get("pulledAt").isJsonNull())
		{
			try
			{
				pulledAt = Math.max(0L, c.get("pulledAt").getAsLong());
			}
			catch (RuntimeException ignored)
			{
				pulledAt = null;
			}
		}
		String source = JsonObjects.text(c, "source");
		return new PackCardResult(name.trim(), foil, instanceId, tierLabel, score, imagePath, foilImagePath,
			artistName, artistColor, artistUrl, examine, condition, pulledBy, pulledAt, source, wikiPage,
			displayName);
	}
}

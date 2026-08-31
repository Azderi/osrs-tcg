package com.osrstcg.cloud.catalog;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.state.PackCardResult;
import java.util.UUID;

/** Parses {@code POST /packs/open} {@code cards[]} elements into {@link PackCardResult}. */
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
		boolean foil = JsonObjects.readBoolean(c, "foil");
		String instanceId = JsonObjects.text(c, "instanceId");
		if (instanceId == null || instanceId.isBlank())
		{
			instanceId = UUID.randomUUID().toString();
		}
		String tierLabel = JsonObjects.text(c, "tierLabel");
		long score = Math.max(0L, JsonObjects.readLong(c, "score"));
		String imagePath = JsonObjects.text(c, "imagePath");
		String foilImagePath = JsonObjects.text(c, "foilImagePath");
		String artistName = JsonObjects.text(c, "artistName");
		String artistColor = JsonObjects.text(c, "artistColor");
		String artistUrl = JsonObjects.text(c, "artistUrl");
		String examine = JsonObjects.text(c, "examine");
		String wikiPage = JsonObjects.text(c, "wikiPage");
		Double condition = JsonObjects.readNullableDouble(c, "condition");
		String pulledBy = JsonObjects.text(c, "pulledBy");
		Double pulledAtNumber = JsonObjects.readNumber(c, "pulledAt");
		Long pulledAt = pulledAtNumber == null ? null : Math.max(0L, Math.round(pulledAtNumber));
		String source = JsonObjects.text(c, "source");
		return new PackCardResult(name.trim(), foil, instanceId, tierLabel, score, imagePath, foilImagePath,
			artistName, artistColor, artistUrl, examine, condition, pulledBy, pulledAt, source, wikiPage,
			displayName);
	}
}

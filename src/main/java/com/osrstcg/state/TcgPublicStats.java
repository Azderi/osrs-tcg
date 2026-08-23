package com.osrstcg.state;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import lombok.Value;

/** Collection stats shown for {@code !tcg} chat command (matches sidebar overview semantics). */
@Value
public class TcgPublicStats
{
	long collectionScore;
	double completionPct;
	int uniqueOwned;
	int uniqueFoilOwned;
	double foilCompletionPct;
	int totalCardPool;
	long openedPacks;
	int totalCardsOwned;
	long foilOwned;
	boolean customRates;

	/** Parse flat {@code GET /api/v1/players/:name/stats} JSON (or equivalent). */
	public static TcgPublicStats fromPlayerStatsJson(JsonObject json)
	{
		if (json == null)
		{
			return null;
		}
		return new TcgPublicStats(
			JsonObjects.readLong(json, "collectionScore"),
			JsonObjects.readDouble(json, "completionPct"),
			JsonObjects.readInt(json, "uniqueOwned"),
			JsonObjects.readInt(json, "uniqueFoilOwned"),
			JsonObjects.readDouble(json, "foilCompletionPct"),
			JsonObjects.readInt(json, "totalCardPool"),
			JsonObjects.readLong(json, "openedPacks"),
			JsonObjects.readInt(json, "totalCardsOwned"),
			JsonObjects.readLong(json, "foilOwned"),
			json.has("customRates") && !json.get("customRates").isJsonNull()
				&& json.get("customRates").getAsBoolean());
	}
}

package com.osrstcg.state;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;

@Value
public class CloudSidebarCollectionStats
{
	int uniqueOwned;
	int uniqueFoilOwned;
	int totalCardsOwned;
	long foilOwned;
	int totalCardPool;
	double completionPct;
	double foilCompletionPct;
	long collectionScore;

	public static CloudSidebarCollectionStats fromStatsJson(JsonObject stats)
	{
		if (stats == null)
		{
			return null;
		}
		Double uniqueOwned = JsonObjects.readNumber(stats, "uniqueOwned", "uniqueCardCount");
		Double totalCardsOwned = JsonObjects.readNumber(stats, "totalCardsOwned", "cardCount");
		Double foilOwned = JsonObjects.readNumber(stats, "foilOwned", "foilCount");
		Double completionPct = JsonObjects.readNumber(stats, "completionPct", "completionPercent");
		Double collectionScore = JsonObjects.readNumber(stats, "collectionScore", "score");
		return new CloudSidebarCollectionStats(
			uniqueOwned == null ? 0 : (int) Math.round(uniqueOwned),
			JsonObjects.readInt(stats, "uniqueFoilOwned"),
			totalCardsOwned == null ? 0 : (int) Math.round(totalCardsOwned),
			foilOwned == null ? 0L : Math.round(foilOwned),
			JsonObjects.readInt(stats, "totalCardPool"),
			completionPct == null ? 0.0d : completionPct,
			JsonObjects.readDouble(stats, "foilCompletionPct"),
			collectionScore == null ? 0L : Math.round(collectionScore));
	}

	/**
	 * True when this object is a real collection overview payload.
	 * Ignores loose aliases like {@code score}/{@code cardCount} alone - those appear on pack-open JSON.
	 */
	public static boolean hasCollectionFields(JsonObject stats)
	{
		if (stats == null)
		{
			return false;
		}
		return stats.has("uniqueOwned")
			|| stats.has("uniqueCardCount")
			|| stats.has("uniqueFoilOwned")
			|| stats.has("totalCardsOwned")
			|| stats.has("foilOwned")
			|| stats.has("totalCardPool")
			|| stats.has("completionPct")
			|| stats.has("foilCompletionPct")
			|| stats.has("collectionScore");
	}

	public static CloudSidebarCollectionStats withOptimisticPackPulls(
		CloudSidebarCollectionStats base,
		Map<CardCollectionKey, Integer> ownedBefore,
		List<PackCardResult> pulls)
	{
		if (base == null || pulls == null || pulls.isEmpty())
		{
			return base;
		}

		Map<CardCollectionKey, Integer> owned = ownedBefore == null
			? new HashMap<>()
			: new HashMap<>(ownedBefore);

		int uniqueOwned = base.getUniqueOwned();
		int uniqueFoilOwned = base.getUniqueFoilOwned();
		int totalCardsOwned = base.getTotalCardsOwned();
		long foilOwned = base.getFoilOwned();
		long collectionScore = base.getCollectionScore();
		int totalCardPool = base.getTotalCardPool();

		for (PackCardResult pull : pulls)
		{
			if (pull == null || pull.getCardName() == null || pull.getCardName().isBlank())
			{
				continue;
			}
			String name = pull.getCardName().trim();
			boolean foil = pull.isFoil();
			int nameQtyBefore = qtyByName(owned, name);
			int foilQtyBefore = qty(owned, name, true);
			long pullScore = Math.max(0L, pull.getScore());

			owned.merge(new CardCollectionKey(name, foil), 1, Integer::sum);
			totalCardsOwned++;
			if (foil)
			{
				foilOwned++;
			}

			if (nameQtyBefore <= 0)
			{
				uniqueOwned++;
				// Pull score is already display score (foil-adjusted when foil).
				collectionScore += pullScore;
			}
			// Foil upgrade on an already-owned name: leave score; next /me/stats reconciles.

			if (foil && foilQtyBefore <= 0)
			{
				uniqueFoilOwned++;
			}
		}

		double completionPct = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueOwned) / totalCardPool;
		double foilCompletionPct = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueFoilOwned) / totalCardPool;
		return new CloudSidebarCollectionStats(
			Math.max(0, uniqueOwned),
			Math.max(0, uniqueFoilOwned),
			Math.max(0, totalCardsOwned),
			Math.max(0L, foilOwned),
			totalCardPool,
			completionPct,
			foilCompletionPct,
			Math.max(0L, collectionScore));
	}

	public static boolean countsAgree(CloudSidebarCollectionStats server, CloudSidebarCollectionStats local)
	{
		if (server == null || local == null)
		{
			return true;
		}
		return server.getUniqueOwned() == local.getUniqueOwned()
			&& server.getUniqueFoilOwned() == local.getUniqueFoilOwned()
			&& server.getTotalCardsOwned() == local.getTotalCardsOwned()
			&& server.getFoilOwned() == local.getFoilOwned();
	}

	private static int qtyByName(Map<CardCollectionKey, Integer> owned, String cardName)
	{
		return qty(owned, cardName, false) + qty(owned, cardName, true);
	}

	private static int qty(Map<CardCollectionKey, Integer> owned, String cardName, boolean foil)
	{
		if (owned == null || cardName == null)
		{
			return 0;
		}
		Integer n = owned.get(new CardCollectionKey(cardName, foil));
		return n == null ? 0 : Math.max(0, n);
	}
}

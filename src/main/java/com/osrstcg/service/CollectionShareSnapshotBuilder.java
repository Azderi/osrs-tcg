package com.osrstcg.service;

import com.osrstcg.model.CardEntry;
import com.osrstcg.model.CardEntrySerializer;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.TcgPublicStats;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the share-safe JSON snapshot for {@code PUT /shares/{id}/collection}.
 * Schema v2 uses compact {@code cardEntries} (same variant shape as profile persistence).
 * The JSON is {@link com.osrstcg.persist.TcgStateStorageEncoding encoded} before upload.
 */
public final class CollectionShareSnapshotBuilder
{
	public static final int SCHEMA_VERSION = 2;

	private CollectionShareSnapshotBuilder()
	{
	}

	public static List<CardEntry> buildCardEntries(CollectionState collectionState)
	{
		return CardEntrySerializer.buildShareEntries(collectionState);
	}

	public static Map<String, Object> statsObject(TcgPublicStats stats)
	{
		Map<String, Object> out = new LinkedHashMap<>();
		if (stats == null)
		{
			out.put("collectionScore", 0);
			out.put("completionPct", 0.0d);
			out.put("uniqueOwned", 0);
			out.put("uniqueFoilOwned", 0);
			out.put("foilCompletionPct", 0.0d);
			out.put("totalCardPool", 0);
			out.put("openedPacks", 0);
			out.put("totalCardsOwned", 0);
			out.put("customRates", false);
			return out;
		}
		out.put("collectionScore", stats.getCollectionScore());
		out.put("completionPct", stats.getCompletionPct());
		out.put("uniqueOwned", stats.getUniqueOwned());
		out.put("uniqueFoilOwned", stats.getUniqueFoilOwned());
		out.put("foilCompletionPct", stats.getFoilCompletionPct());
		out.put("totalCardPool", stats.getTotalCardPool());
		out.put("openedPacks", stats.getOpenedPacks());
		out.put("totalCardsOwned", stats.getTotalCardsOwned());
		out.put("customRates", stats.isCustomRates());
		return out;
	}

	public static Map<String, Object> buildPayload(
		String catalogVersion,
		String displayName,
		TcgPublicStats stats,
		CollectionState collectionState,
		Instant updatedAt)
	{
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schemaVersion", SCHEMA_VERSION);
		payload.put("catalogVersion", catalogVersion == null || catalogVersion.isEmpty() ? "unknown" : catalogVersion);
		payload.put("displayName", displayName);
		payload.put("updatedAt", (updatedAt == null ? Instant.now() : updatedAt).toString());
		payload.put("stats", statsObject(stats));
		payload.put("cardEntries", buildCardEntries(collectionState));
		return payload;
	}
}

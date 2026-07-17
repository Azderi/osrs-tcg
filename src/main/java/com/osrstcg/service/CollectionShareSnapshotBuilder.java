package com.osrstcg.service;

import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.TcgPublicStats;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the share-safe JSON payload for {@code PUT /shares/{id}/collection}.
 * Includes per-copy {@code instances} (pull provenance) and aggregated {@code cards};
 * excludes {@code DEBUG_} provenance.
 */
public final class CollectionShareSnapshotBuilder
{
	public static final int SCHEMA_VERSION = 1;

	private CollectionShareSnapshotBuilder()
	{
	}

	/**
	 * Share-safe instance rows (one per owned copy). Omits DEBUG_ provenance.
	 */
	public static List<Map<String, Object>> buildInstances(CollectionState collectionState)
	{
		if (collectionState == null)
		{
			return List.of();
		}
		List<OwnedCardInstance> shareSafe = new ArrayList<>();
		for (OwnedCardInstance instance : collectionState.getOwnedInstances())
		{
			if (instance == null || OwnedCardInstance.hasDebugPullMetadata(instance.getPulledByUsername()))
			{
				continue;
			}
			String name = instance.getCardName();
			if (name == null || name.trim().isEmpty())
			{
				continue;
			}
			shareSafe.add(instance);
		}
		shareSafe.sort(Comparator
			.comparing(OwnedCardInstance::getCardName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(OwnedCardInstance::isFoil)
			.thenComparingLong(OwnedCardInstance::getPulledAtEpochMs)
			.thenComparing(OwnedCardInstance::getInstanceId, Comparator.nullsLast(String::compareTo)));

		List<Map<String, Object>> rows = new ArrayList<>(shareSafe.size());
		for (OwnedCardInstance instance : shareSafe)
		{
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("instanceId", instance.getInstanceId());
			row.put("cardName", instance.getCardName().trim());
			row.put("foil", instance.isFoil());
			String pulledBy = instance.getPulledByUsername();
			if (pulledBy != null)
			{
				pulledBy = pulledBy.trim();
			}
			if (pulledBy != null && !pulledBy.isEmpty())
			{
				row.put("pulledBy", pulledBy);
			}
			row.put("pulledAt", instance.getPulledAtEpochMs());
			if (instance.isLocked())
			{
				row.put("locked", true);
			}
			rows.add(row);
		}
		return rows;
	}

	/**
	 * Aggregates non-debug owned instances into share card rows.
	 */
	public static List<Map<String, Object>> aggregateCards(CollectionState collectionState)
	{
		if (collectionState == null)
		{
			return List.of();
		}
		CollectionState shareSafe = collectionState.withoutDebugProvenanceRows();
		List<Map<String, Object>> cards = new ArrayList<>();
		List<Map.Entry<CardCollectionKey, Integer>> entries = new ArrayList<>(shareSafe.getOwnedCards().entrySet());
		entries.sort(Comparator
			.comparing((Map.Entry<CardCollectionKey, Integer> e) -> e.getKey().getCardName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(e -> e.getKey().isFoil()));
		for (Map.Entry<CardCollectionKey, Integer> entry : entries)
		{
			Integer qty = entry.getValue();
			if (qty == null || qty < 1)
			{
				continue;
			}
			CardCollectionKey key = entry.getKey();
			String name = key.getCardName();
			if (name == null || name.trim().isEmpty())
			{
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("cardName", name.trim());
			row.put("foil", key.isFoil());
			row.put("quantity", qty);
			cards.add(row);
		}
		return cards;
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
		payload.put("cards", aggregateCards(collectionState));
		payload.put("instances", buildInstances(collectionState));
		return payload;
	}
}

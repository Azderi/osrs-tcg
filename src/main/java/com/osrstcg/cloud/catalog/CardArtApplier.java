package com.osrstcg.cloud.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.cloud.api.JsonObjects;
import java.util.List;

/**
 * Applies accepted card-art overlays from {@code GET /api/v1/catalog/card-art}.
 * Overlay is the sole source of custom {@code foilImagePath} and {@code examine}.
 * {@code examine} Does not rewrite on-disk catalog JSON -
 * only mutates the in-memory {@link CardDefinition} list after load.
 *
 * Item and NPC numeric ids can collide. Lookups by id only apply when the overlay
 * entry's {@code cardName} matches the catalog card.
 */
public final class CardArtApplier
{
	private CardArtApplier()
	{
	}

	/**
	 * Mutates {@code cards} in place. Keys preferred: card name; falls back to
	 * {@code String(card.id)} when {@code cardName} on the entry matches.
	 */
	public static void apply(List<CardDefinition> cards, JsonObject overlay)
	{
		if (cards == null || cards.isEmpty() || overlay == null
			|| !overlay.has("cards") || !overlay.get("cards").isJsonObject())
		{
			return;
		}
		JsonObject map = overlay.getAsJsonObject("cards");
		for (CardDefinition card : cards)
		{
			if (card == null)
			{
				continue;
			}
			JsonObject hit = null;
			if (card.getName() != null && !card.getName().isBlank())
			{
				hit = matchingEntry(asObject(map.get(card.getName())), card);
			}
			if (hit == null && card.getId() != null)
			{
				hit = matchingEntry(asObject(map.get(Long.toString(card.getId()))), card);
			}
			if (hit == null)
			{
				continue;
			}
			String foilImagePath = JsonObjects.textTrimmed(hit, "foilImagePath");
			if (foilImagePath != null)
			{
				card.setFoilImagePath(foilImagePath);
			}
			String artistName = JsonObjects.textTrimmed(hit, "artistName");
			if (artistName != null)
			{
				card.setArtistName(artistName);
			}
			String artistUrl = JsonObjects.textTrimmed(hit, "artistUrl");
			if (artistUrl != null)
			{
				card.setArtistUrl(artistUrl);
			}
			String artistColor = JsonObjects.textTrimmed(hit, "artistColor");
			if (artistColor != null)
			{
				card.setArtistColor(artistColor);
			}
			String examine = examineText(hit, "examine");
			if (examine != null)
			{
				card.setExamine(examine);
			}
		}
	}

	/**
	 * Require overlay {@code cardName} to match the catalog card (item/NPC id collisions).
	 */
	private static JsonObject matchingEntry(JsonObject hit, CardDefinition card)
	{
		if (hit == null)
		{
			return null;
		}
		String hitName = JsonObjects.textTrimmed(hit, "cardName");
		if (hitName == null)
		{
			// Id-only entries are ambiguous across item/NPC namespaces.
			return null;
		}
		String cardName = card.getName();
		if (cardName != null && cardName.trim().equalsIgnoreCase(hitName))
		{
			return hit;
		}
		return null;
	}

	private static JsonObject asObject(JsonElement el)
	{
		return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
	}

	/** Like {@link JsonObjects#text} but normalizes CR/LF and keeps internal newlines for multi-line examine. */
	private static String examineText(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || o.get(key).isJsonNull())
		{
			return null;
		}
		try
		{
			String value = o.get(key).getAsString();
			if (value == null)
			{
				return null;
			}
			String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
			return normalized.isEmpty() ? null : normalized;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}

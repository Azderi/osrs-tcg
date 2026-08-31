package com.osrstcg.cloud.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.cloud.api.JsonObjects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts public {@code GET /api/v1/catalog/cards/live} raw {@code { items, npcs }} into
 * plugin {@link CardDefinition}s (one card per parent name; {@code tcg.variants} ids retained).
 */
public final class LiveCardsCatalogParser
{
	private LiveCardsCatalogParser()
	{
	}

	public static List<CardDefinition> parse(JsonObject liveJson)
	{
		if (liveJson == null)
		{
			return List.of();
		}
		JsonArray items = liveJson.has("items") && liveJson.get("items").isJsonArray()
			? liveJson.getAsJsonArray("items")
			: new JsonArray();
		JsonArray npcs = liveJson.has("npcs") && liveJson.get("npcs").isJsonArray()
			? liveJson.getAsJsonArray("npcs")
			: new JsonArray();

		Set<String> itemNames = new HashSet<>();
		for (JsonElement el : items)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			String name = JsonObjects.textTrimmed(el.getAsJsonObject(), "name");
			if (name != null)
			{
				itemNames.add(name);
			}
		}

		List<CardDefinition> cards = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonElement el : items)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			CardDefinition card = normalizeParent(el.getAsJsonObject(), false, itemNames);
			if (card == null || !seen.add(card.getName()))
			{
				continue;
			}
			cards.add(card);
		}
		for (JsonElement el : npcs)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			CardDefinition card = normalizeParent(el.getAsJsonObject(), true, itemNames);
			if (card == null || !seen.add(card.getName()))
			{
				continue;
			}
			cards.add(card);
		}
		return cards;
	}

	private static CardDefinition normalizeParent(JsonObject raw, boolean npc, Set<String> itemNames)
	{
		String name = JsonObjects.textTrimmed(raw, "name");
		if (name == null)
		{
			return null;
		}

		JsonObject tcg = raw.has("tcg") && raw.get("tcg").isJsonObject()
			? raw.getAsJsonObject("tcg")
			: new JsonObject();
		JsonObject tags = tcg.has("tags") && tcg.get("tags").isJsonObject()
			? tcg.getAsJsonObject("tags")
			: new JsonObject();

		List<String> category = new ArrayList<>();
		if (tags.has("labels") && tags.get("labels").isJsonArray())
		{
			for (JsonElement labelEl : tags.getAsJsonArray("labels"))
			{
				if (labelEl.isJsonPrimitive() && labelEl.getAsJsonPrimitive().isString())
				{
					String label = labelEl.getAsString().trim();
					if (!label.isEmpty())
					{
						category.add(label);
					}
				}
			}
		}
		String slot = JsonObjects.textTrimmed(tags, "slot");
		if (slot != null)
		{
			category.add(slot);
		}
		String combatStyle = JsonObjects.textTrimmed(tags, "combatStyle");
		if (combatStyle != null)
		{
			category.add(combatStyle);
		}
		if (npc)
		{
			category.add("NPC");
		}

		Double idNumber = JsonObjects.readNumber(raw, "id");
		Long id = idNumber == null ? null : Math.round(idNumber);
		String cardName = name;
		if (npc && itemNames.contains(name))
		{
			if (id == null)
			{
				return null;
			}
			cardName = "npc:" + id;
		}

		CardDefinition card = new CardDefinition();
		card.setId(id);
		card.setName(cardName);
		card.setDisplayName(name);
		card.setCategory(category);
		// Region tags exist on both items and NPCs in live JSON (pack filters / shop progress).
		List<String> regions = parseStringList(raw, "regions");
		if (!regions.isEmpty())
		{
			card.setRegions(regions);
		}
		card.setExamine(JsonObjects.textTrimmed(raw, "examine"));
		String imagePath = JsonObjects.textTrimmed(raw, "imagePath");
		if (imagePath != null)
		{
			card.setImageUrl(imagePath);
		}
		JsonObject wiki = raw.has("wiki") && raw.get("wiki").isJsonObject()
			? raw.getAsJsonObject("wiki")
			: null;
		String wikiPage = JsonObjects.textTrimmed(wiki, "page");
		if (wikiPage != null)
		{
			card.setWikiPage(wikiPage);
		}
		Double scoreNumber = JsonObjects.readNumber(tcg, "score");
		if (scoreNumber != null)
		{
			card.setScore(Math.round(scoreNumber));
		}
		Double foilScoreNumber = JsonObjects.readNumber(tcg, "foilScore");
		Long foilScore = foilScoreNumber == null ? null : Math.round(foilScoreNumber);
		if (foilScore != null && foilScore >= 0L)
		{
			card.setFoilScore(foilScore);
		}
		Double overrideFoilScoreNumber = JsonObjects.readNumber(tcg, "overrideFoilScore");
		Long overrideFoilScore = overrideFoilScoreNumber == null ? null : Math.round(overrideFoilScoreNumber);
		if (overrideFoilScore != null && overrideFoilScore >= 0L && card.getFoilScore() == null)
		{
			card.setFoilScore(overrideFoilScore);
		}
		String tierLabel = JsonObjects.textTrimmed(tcg, "tierLabel");
		if (tierLabel != null)
		{
			card.setTierLabel(tierLabel);
		}
		List<Long> variantIds = parseVariantIds(tcg);
		if (!variantIds.isEmpty())
		{
			card.setVariantIds(variantIds);
		}
		return card;
	}

	/** Distinct {@code tcg.variants[].id} values in encounter order. */
	private static List<Long> parseVariantIds(JsonObject tcg)
	{
		if (tcg == null || !tcg.has("variants") || !tcg.get("variants").isJsonArray())
		{
			return List.of();
		}
		List<Long> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (JsonElement el : tcg.getAsJsonArray("variants"))
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			Double variantIdNumber = JsonObjects.readNumber(el.getAsJsonObject(), "id");
			Long variantId = variantIdNumber == null ? null : Math.round(variantIdNumber);
			if (variantId == null || !seen.add(variantId))
			{
				continue;
			}
			out.add(variantId);
		}
		return out;
	}

	private static List<String> parseStringList(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || !o.get(key).isJsonArray())
		{
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray(key))
		{
			if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString())
			{
				continue;
			}
			String value = el.getAsString().trim();
			if (!value.isEmpty())
			{
				out.add(value);
			}
		}
		return out;
	}
}

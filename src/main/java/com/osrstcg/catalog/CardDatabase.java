package com.osrstcg.catalog;

import com.osrstcg.util.HtmlEntities;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory holder for the loaded card catalog. Owns the normalized card list plus indexes
 * (lookup by name, chat rarity color by name) rebuilt whenever the catalog is replaced.
 */
@Singleton
@Slf4j
public class CardDatabase
{
	private List<CardDefinition> cards = Collections.emptyList();
	private Map<String, Color> chatRarityColors = Map.of();
	private Map<String, CardDefinition> byLowerCaseName = Map.of();

	/** No-arg constructor for DI. */
	@Inject
	public CardDatabase()
	{
	}

	/** The current catalog, in load order. */
	public synchronized List<CardDefinition> getCards()
	{
		return cards;
	}

	/** Card counts grouped by primary category, in first-seen order. */
	public synchronized Map<String, Long> categoryCounts()
	{
		return cards.stream()
			.collect(Collectors.groupingBy(
				card -> safeCategory(card.getPrimaryCategory()),
				LinkedHashMap::new,
				Collectors.counting()
			));
	}

	/** Number of cards currently loaded. */
	public synchronized int size()
	{
		return cards.size();
	}

	/** Case-insensitive lookup by trimmed card name; first card wins on duplicate names. */
	public synchronized Optional<CardDefinition> findByName(String cardName)
	{
		if (isBlank(cardName))
		{
			return Optional.empty();
		}
		String key = cardName.trim().toLowerCase(Locale.ROOT);
		return Optional.ofNullable(byLowerCaseName.get(key));
	}

	/** Normalizes and swaps in a new catalog, rebuilds the lookup indexes, and logs the load. */
	public synchronized void replaceCards(List<CardDefinition> incoming, String sourceLabel)
	{
		List<CardDefinition> normalized = normalize(incoming == null ? List.of() : incoming);
		cards = Collections.unmodifiableList(normalized);
		rebuildIndexes();
		log.info("Loaded {} cards from {}", cards.size(),
			sourceLabel == null || sourceLabel.isBlank() ? "catalog" : sourceLabel);
	}

	/** Chat prefix color for {@code cardName}'s rarity tier, or {@link Color#WHITE} if unknown/blank. */
	public synchronized Color chatRarityColorForCardName(String cardName)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return Color.WHITE;
		}
		Color c = chatRarityColors.get(cardName.trim().toLowerCase(Locale.ROOT));
		return c != null ? c : Color.WHITE;
	}

	/** Rebuilds {@link #chatRarityColors} and {@link #byLowerCaseName} from {@link #cards}; Godly uses the default chat prefix color instead of its tier color. */
	private void rebuildIndexes()
	{
		if (cards.isEmpty())
		{
			chatRarityColors = Map.of();
			byLowerCaseName = Map.of();
			return;
		}
		Map<String, Color> chatMap = new HashMap<>();
		Map<String, CardDefinition> nameMap = new HashMap<>();
		for (CardDefinition c : cards)
		{
			if (c == null || c.getName() == null || c.getName().trim().isEmpty())
			{
				continue;
			}
			String key = c.getName().trim().toLowerCase(Locale.ROOT);
			nameMap.putIfAbsent(key, c);
			RarityMath.Tier t = RarityMath.tierFromLabel(c.getTierLabel());
			Color displayColor = t.getColor();
			Color chatColor = t == RarityMath.Tier.GODLY
				? TcgPluginGameMessages.DEFAULT_PREFIX_COLOR
				: displayColor;
			chatMap.putIfAbsent(key, chatColor);
		}
		chatRarityColors = Collections.unmodifiableMap(chatMap);
		byLowerCaseName = Collections.unmodifiableMap(nameMap);
	}

	/** Drops nameless cards, decodes HTML entities in name/examine text, and normalizes tags and image paths for the rest. */
	private List<CardDefinition> normalize(List<CardDefinition> parsed)
	{
		List<CardDefinition> normalized = new ArrayList<>();
		Map<String, Integer> seenNameCounts = new HashMap<>();

		for (CardDefinition card : parsed)
		{
			if (card == null || isBlank(card.getName()))
			{
				continue;
			}

			card.setName(HtmlEntities.decode(card.getName().trim()));
			normalizeCategoryTags(card);
			if (card.getExamine() != null)
			{
				card.setExamine(HtmlEntities.decode(card.getExamine().trim()));
			}
			card.setImageUrl(normalizeImageUrl(card.getImageUrl()));
			if (card.getFoilImagePath() != null)
			{
				card.setFoilImagePath(normalizeFoilImagePath(card.getFoilImagePath()));
			}

			normalized.add(card);
			seenNameCounts.put(card.getName(), seenNameCounts.getOrDefault(card.getName(), 0) + 1);
		}

		long duplicates = seenNameCounts.values().stream().filter(count -> count > 1).count();
		if (duplicates > 0)
		{
			log.debug("Card catalog contains {} duplicate card names", duplicates);
		}

		return normalized;
	}

	/** Trims {@code raw}, returning {@code null} for a null or blank value. */
	static String normalizeImageUrl(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String url = raw.trim();
		return url.isEmpty() ? null : url;
	}

	/** Same trimming rule as {@link #normalizeImageUrl}, applied to a foil image path. */
	static String normalizeFoilImagePath(String raw)
	{
		return normalizeImageUrl(raw);
	}

	/** Replaces {@code card}'s category list with a trimmed, blank-filtered copy (empty list if null). */
	private static void normalizeCategoryTags(CardDefinition card)
	{
		List<String> raw = card.getCategory();
		if (raw == null)
		{
			card.setCategory(new ArrayList<>());
			return;
		}
		List<String> trimmed = new ArrayList<>();
		for (String t : raw)
		{
			if (t != null && !t.trim().isEmpty())
			{
				trimmed.add(t.trim());
			}
		}
		card.setCategory(trimmed);
	}

	/** {@code rawCategory} trimmed, or {@code "Unknown"} if blank. */
	private static String safeCategory(String rawCategory)
	{
		return isBlank(rawCategory) ? "Unknown" : rawCategory.trim();
	}

	/** Whether {@code value} is null or all-whitespace. */
	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}
}

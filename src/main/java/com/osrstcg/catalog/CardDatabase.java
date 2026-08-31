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

@Singleton
@Slf4j
public class CardDatabase
{
	private List<CardDefinition> cards = Collections.emptyList();
	private Map<String, Color> chatRarityColorByLowerCaseName = Map.of();
	private Map<String, CardDefinition> byLowerCaseName = Map.of();

	@Inject
	public CardDatabase()
	{
	}

	public synchronized List<CardDefinition> getCards()
	{
		return cards;
	}

	public synchronized Map<String, Long> categoryCounts()
	{
		return cards.stream()
			.collect(Collectors.groupingBy(
				card -> safeCategory(card.getPrimaryCategory()),
				LinkedHashMap::new,
				Collectors.counting()
			));
	}

	public synchronized int size()
	{
		return cards.size();
	}

	public synchronized Optional<CardDefinition> findByName(String cardName)
	{
		if (isBlank(cardName))
		{
			return Optional.empty();
		}
		String key = cardName.trim().toLowerCase(Locale.ROOT);
		return Optional.ofNullable(byLowerCaseName.get(key));
	}

	public synchronized void replaceCards(List<CardDefinition> incoming, String sourceLabel)
	{
		List<CardDefinition> normalized = normalize(incoming == null ? List.of() : incoming);
		cards = Collections.unmodifiableList(normalized);
		rebuildIndexes();
		log.info("Loaded {} cards from {}", cards.size(),
			sourceLabel == null || sourceLabel.isBlank() ? "catalog" : sourceLabel);
	}

	public synchronized Color chatRarityColorForCardName(String cardName)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return Color.WHITE;
		}
		Color c = chatRarityColorByLowerCaseName.get(cardName.trim().toLowerCase(Locale.ROOT));
		return c != null ? c : Color.WHITE;
	}

	private void rebuildIndexes()
	{
		if (cards.isEmpty())
		{
			chatRarityColorByLowerCaseName = Map.of();
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
				? TcgPluginGameMessages.CHAT_EMPHASIS_GOLD
				: displayColor;
			chatMap.putIfAbsent(key, chatColor);
		}
		chatRarityColorByLowerCaseName = Collections.unmodifiableMap(chatMap);
		byLowerCaseName = Collections.unmodifiableMap(nameMap);
	}

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

	static String normalizeImageUrl(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String url = raw.trim();
		return url.isEmpty() ? null : url;
	}

	static String normalizeFoilImagePath(String raw)
	{
		return normalizeImageUrl(raw);
	}

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

	private static String safeCategory(String rawCategory)
	{
		return isBlank(rawCategory) ? "Unknown" : rawCategory.trim();
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}
}

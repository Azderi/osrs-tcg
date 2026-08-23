package com.osrstcg.catalog;

import com.osrstcg.util.HtmlEntities;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * In-memory card definitions. Populated from the live web catalog
 * ({@code GET /api/v1/catalog/cards/live}) via {@link com.osrstcg.cloud.catalog.CardCatalogService},
 * or from the disk cache of that fetch - not from a bundled plugin resource.
 */
@Singleton
@Slf4j
public class CardDatabase
{
	private List<CardDefinition> cards = Collections.emptyList();
	private boolean loaded;
	private Map<String, Color> chatRarityColorByLowerCaseName = Map.of();
	private Map<String, CardDefinition> byLowerCaseName = Map.of();

	@Inject
	public CardDatabase()
	{
	}

	/**
	 * No-op placeholder for older call sites. Catalog is applied via {@link #replaceCards}
	 * after disk cache load or network fetch.
	 */
	public synchronized void load()
	{
		// Catalog comes from CardCatalogService (disk / network), not classpath.
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

	public synchronized boolean isLoaded()
	{
		return loaded && !cards.isEmpty();
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

	/**
	 * Replace the in-memory catalog (network fetch or disk cache).
	 */
	public synchronized void replaceCards(List<CardDefinition> incoming, String sourceLabel)
	{
		List<CardDefinition> normalized = normalize(incoming == null ? List.of() : incoming);
		cards = Collections.unmodifiableList(normalized);
		loaded = true;
		rebuildIndexes();
		log.info("Loaded {} cards from {}", cards.size(),
			sourceLabel == null || sourceLabel.isBlank() ? "catalog" : sourceLabel);
	}

	/**
	 * Display-tier colour for chat (same tier source as the collection album / pack reveal). Godly uses
	 * {@link TcgPluginGameMessages#CHAT_EMPHASIS_GOLD} to match the {@code OSRS TCG} label.
	 */
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
			card.setImageUrl(normalizeImageUrl(card.getImageUrl(), isMonsterCard(card)));
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

	/**
	 * Prefer web-relative CDN paths. Converts legacy wiki thumb URLs when a remote catalog
	 * still ships them.
	 */
	static String normalizeImageUrl(String raw, boolean monster)
	{
		if (raw == null)
		{
			return null;
		}
		String url = raw.trim();
		if (url.isEmpty())
		{
			return null;
		}
		if (url.startsWith("/images/"))
		{
			return url;
		}
		String filename = extractWikiThumbFilename(url);
		if (filename.isEmpty() && (url.startsWith("http://") || url.startsWith("https://")))
		{
			// Absolute non-wiki CDN URL - keep as-is.
			return url;
		}
		if (!filename.isEmpty())
		{
			String folder = monster ? "/images/npcs/detail/" : "/images/items/detail/";
			return folder + filename;
		}
		return url;
	}

	/** Keep foil art as relative API/CDN paths; never rewrite to wiki thumbs. */
	static String normalizeFoilImagePath(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String url = raw.trim();
		if (url.isEmpty())
		{
			return null;
		}
		return url;
	}

	private static String extractWikiThumbFilename(String url)
	{
		String marker = "/images/thumb/";
		int i = url.indexOf(marker);
		if (i < 0)
		{
			return "";
		}
		String tail = url.substring(i + marker.length());
		int slash = tail.indexOf('/');
		String encoded = slash > 0 ? tail.substring(0, slash) : tail;
		try
		{
			return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
		}
		catch (Exception ex)
		{
			return encoded;
		}
	}

	private static boolean isMonsterCard(CardDefinition card)
	{
		for (String t : card.getCategoryTags())
		{
			if (t != null && "monster".equalsIgnoreCase(t.trim()))
			{
				return true;
			}
		}
		return false;
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

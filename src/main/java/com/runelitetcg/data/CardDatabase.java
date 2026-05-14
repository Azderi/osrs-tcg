package com.runelitetcg.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class CardDatabase
{
	private static final Type CARD_LIST_TYPE = new TypeToken<List<CardDefinition>>() { }.getType();

	private final Gson gson;
	private List<CardDefinition> cards = Collections.emptyList();
	private boolean loaded;

	@Inject
	public CardDatabase(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load()
	{
		if (loaded)
		{
			return;
		}

		List<CardDefinition> loadedCards = loadFromClasspath();

		cards = Collections.unmodifiableList(loadedCards);
		loaded = true;
		log.info("Loaded {} cards from Card.json", cards.size());
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

	public synchronized void setCardsForTesting(List<CardDefinition> testCards)
	{
		cards = Collections.unmodifiableList(new ArrayList<>(testCards));
		loaded = true;
	}

	private List<CardDefinition> loadFromClasspath()
	{
		try (Reader reader = openClasspathReader())
		{
			if (reader == null)
			{
				return Collections.emptyList();
			}
			return normalize(parse(reader));
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.warn("Failed reading Card.json from classpath", ex);
			return Collections.emptyList();
		}
	}

	private Reader openClasspathReader()
	{
		InputStream stream = getClass().getResourceAsStream("/Card.json");
		if (stream == null)
		{
			log.warn("Card.json resource missing from plugin classpath");
			return null;
		}
		return new InputStreamReader(stream, StandardCharsets.UTF_8);
	}

	private List<CardDefinition> parse(Reader reader)
	{
		List<CardDefinition> parsed = gson.fromJson(reader, CARD_LIST_TYPE);
		return parsed == null ? Collections.emptyList() : parsed;
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

			card.setName(card.getName().trim());
			normalizeCategoryTags(card);
			if (card.getImageUrl() != null)
			{
				card.setImageUrl(card.getImageUrl().trim());
			}

			normalized.add(card);
			seenNameCounts.put(card.getName(), seenNameCounts.getOrDefault(card.getName(), 0) + 1);
		}

		long duplicates = seenNameCounts.values().stream().filter(count -> count > 1).count();
		if (duplicates > 0)
		{
			log.debug("Card.json contains {} duplicate card names", duplicates);
		}

		return normalized;
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

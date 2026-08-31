package com.osrstcg.catalog;

import com.google.gson.annotations.JsonAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;

@Data
public class BoosterPackDefinition
{
	private String id;
	private String name;
	@JsonAdapter(CategoryListTypeAdapter.class)
	private List<String> category;
	/** Shared album collection label; packs with the same value collapse to one filter. */
	private String collectionName;
	private int price;
	/** Shop icon - web path or legacy classpath filename. */
	private String thumbnail;
	/** Full pack art for reveal overlay - web path. */
	private String image;

	/**
	 * Album filter key: trimmed {@code collectionName} when set, otherwise pack {@code id}.
	 */
	public String getCollectionKey()
	{
		if (collectionName != null && !collectionName.isBlank())
		{
			return collectionName.trim();
		}
		return id;
	}

	public static boolean isHostedImagePath(String path)
	{
		if (path == null || path.isBlank())
		{
			return false;
		}
		String t = path.trim();
		return t.startsWith("/") || t.startsWith("https://");
	}

	public String revealSleevePath()
	{
		return isHostedImagePath(image) ? image.trim() : null;
	}

	public List<String> getCategoryFilters()
	{
		if (category == null)
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (String c : category)
		{
			if (c != null && !c.trim().isEmpty())
			{
				out.add(c.trim());
			}
		}
		return out;
	}

	/**
	 * True if the card matches one of this pack's filters. Filters are OR'd; each filter may list several
	 * {@code &}-separated parts that must all appear among the card's category tags or regions
	 * (after splitting {@code &} on each). When {@code regionFilters} is empty, this is a universal pack:
	 * every roll-eligible card matches.
	 */
	public static boolean cardMatchesRegion(CardDefinition card, List<String> regionFilters)
	{
		if (card == null || regionFilters == null)
		{
			return false;
		}
		if (regionFilters.isEmpty())
		{
			return true;
		}
		Set<String> cardPartKeys = cardPartKeys(card);
		for (String filter : regionFilters)
		{
			if (filter != null && filterMatchesCard(cardPartKeys, filter.trim()))
			{
				return true;
			}
		}
		return false;
	}

	/** Category tags plus geographic regions, expanded/canonicalized like pack filters. */
	static Set<String> cardPartKeys(CardDefinition card)
	{
		Set<String> cardPartKeys = new HashSet<>();
		addCanonicalParts(cardPartKeys, card.getCategoryTags());
		addCanonicalParts(cardPartKeys, card.getRegionTags());
		return cardPartKeys;
	}

	private static void addCanonicalParts(Set<String> into, List<String> rawTags)
	{
		for (String tag : rawTags)
		{
			for (String part : CategoryTagUtil.expandCompoundParts(tag))
			{
				String key = CategoryTagUtil.canonicalKey(part);
				if (!key.isEmpty())
				{
					into.add(key);
				}
			}
		}
	}

	private static boolean filterMatchesCard(Set<String> cardPartKeys, String filter)
	{
		if (filter.isEmpty())
		{
			return false;
		}
		List<String> need = CategoryTagUtil.expandCompoundParts(filter);
		if (need.isEmpty())
		{
			return false;
		}
		for (String part : need)
		{
			String key = CategoryTagUtil.canonicalKey(part);
			if (key.isEmpty() || !cardPartKeys.contains(key))
			{
				return false;
			}
		}
		return true;
	}
}

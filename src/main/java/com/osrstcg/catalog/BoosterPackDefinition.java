package com.osrstcg.catalog;

import com.google.gson.annotations.JsonAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
/**
 * Catalog definition of a purchasable booster pack: identity, price, category/region filters,
 * and the image assets used for its thumbnail and reveal sleeve.
 */
@Data
public class BoosterPackDefinition
{
	private String id;
	private String name;
	@JsonAdapter(CategoryListTypeAdapter.class)
	private List<String> category;
	private String collectionName;
	private int price;
	private String thumbnail;
	private String image;
/** Key used to group this pack's pulls in the collection: {@link #collectionName} if set, else {@link #id}. */
	public String getCollectionKey()
	{
		if (collectionName != null && !collectionName.isBlank())
		{
			return collectionName.trim();
		}
		return id;
	}
/** Whether {@code path} is a hosted asset reference (site-relative or {@code https://}) rather than a bundled resource path. */
	public static boolean isHostedImagePath(String path)
	{
		if (path == null || path.isBlank())
		{
			return false;
		}
		String t = path.trim();
		return t.startsWith("/") || t.startsWith("https://");
	}
/** The pack's reveal-sleeve image path, or {@code null} if {@link #image} isn't a hosted path. */
	public String revealSleevePath()
	{
		return isHostedImagePath(image) ? image.trim() : null;
	}
/** {@link #category}, trimmed of blanks/nulls; empty list if unset. */
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
	 * Whether {@code card} matches at least one filter in {@code regionFilters}, comparing against the
	 * card's combined category and region tags. An empty (non-null) filter list matches everything.
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
/** Canonical keys for every compound-tag part across {@code card}'s category and region tags. */
	static Set<String> cardPartKeys(CardDefinition card)
	{
		Set<String> cardPartKeys = new HashSet<>();
		addCanonicalParts(cardPartKeys, card.getCategoryTags());
		addCanonicalParts(cardPartKeys, card.getRegionTags());
		return cardPartKeys;
	}
/** Expands each tag in {@code rawTags} into its compound parts and adds their canonical keys to {@code into}. */
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
/** Whether every compound part of {@code filter} has a matching canonical key in {@code cardPartKeys}. */
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

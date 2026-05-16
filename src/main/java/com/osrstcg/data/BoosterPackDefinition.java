package com.osrstcg.data;

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
	private int price;

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
	 * {@code &}-separated parts that must all appear among the card's tags (after splitting {@code &} on the card).
	 * When {@code regionFilters} is empty, this is a universal pack: every roll-eligible card matches.
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
		Set<String> cardPartKeys = new HashSet<>();
		for (String cardTag : card.getCategoryTags())
		{
			for (String part : CategoryTagUtil.expandCompoundParts(cardTag))
			{
				String key = CategoryTagUtil.canonicalKey(part);
				if (!key.isEmpty())
				{
					cardPartKeys.add(key);
				}
			}
		}
		for (String filter : regionFilters)
		{
			if (filter != null && filterMatchesCard(cardPartKeys, filter.trim()))
			{
				return true;
			}
		}
		return false;
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

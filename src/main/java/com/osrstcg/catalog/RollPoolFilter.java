package com.osrstcg.catalog;

import java.util.List;

	/**
	 * Roll pool is the loaded live catalog; quest-only item rows are omitted at catalog build time.
	 */
public final class RollPoolFilter
{
	private RollPoolFilter()
	{
	}

	public static List<CardDefinition> filterRollPool(List<CardDefinition> cards)
	{
		if (cards == null || cards.isEmpty())
		{
			return List.of();
		}
		return cards;
	}
}

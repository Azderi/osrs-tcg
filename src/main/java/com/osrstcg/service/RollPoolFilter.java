package com.osrstcg.service;

import com.osrstcg.data.CardDefinition;
import java.util.List;

/**
 * Roll pool is the loaded catalog; quest-only item rows are omitted at {@code Card.json} build time.
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

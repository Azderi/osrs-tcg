package com.osrstcg.util;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.PackCardResult;

/**
 * Resolve a player-facing card title. Storage identity may be {@code npc:{id}}; UI should prefer
 * pull/catalog display names.
 */
public final class CardDisplayNames
{
	private CardDisplayNames()
	{
	}

	public static String firstNonBlank(String... values)
	{
		if (values == null)
		{
			return null;
		}
		for (String value : values)
		{
			if (value != null && !value.isBlank())
			{
				return value.trim();
			}
		}
		return null;
	}

	/**
	 * Title for pack reveal / tips: pull display name, then catalog display name, then identity keys.
	 */
	public static String titleForPull(PackCardResult pull, CardDefinition catalog)
	{
		String pullDisplay = pull == null ? null : pull.getDisplayName();
		String catalogDisplay = catalog == null ? null : catalog.getDisplayName();
		String catalogName = catalog == null ? null : catalog.getName();
		String pullCardName = pull == null ? null : pull.getCardName();
		String title = firstNonBlank(pullDisplay, catalogDisplay, catalogName, pullCardName);
		return title == null || title.isBlank() ? "Unknown Card" : title;
	}

	/** Tip / list title when a materialized definition is available. */
	public static String titleForDefinition(CardDefinition def, PackCardResult pull)
	{
		String defDisplay = def == null ? null : def.getDisplayName();
		String defName = def == null ? null : def.getName();
		String pullDisplay = pull == null ? null : pull.getDisplayName();
		String pullCardName = pull == null ? null : pull.getCardName();
		String title = firstNonBlank(defDisplay, pullDisplay, defName, pullCardName);
		return title == null || title.isBlank() ? "Card" : title;
	}
}

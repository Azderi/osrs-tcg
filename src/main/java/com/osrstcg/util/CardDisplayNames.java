package com.osrstcg.util;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.PackCardResult;

/** Picks the best display name for a card out of several possibly-blank candidate sources. */
public final class CardDisplayNames
{
	/** No instances. */
	private CardDisplayNames()
	{
	}

	/** @return the first non-null, non-blank, trimmed value in {@code values}, or {@code null} if none qualify (including a null array). */
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
	 * Best title for a pulled card, preferring the pull's own display name, then the catalog
	 * definition's display name/name, then the pull's raw card name; falls back to "Unknown Card".
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

	/**
	 * Best title for a catalog definition, preferring the definition's own display name/name, then
	 * the pull's display name/card name; falls back to "Card".
	 */
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

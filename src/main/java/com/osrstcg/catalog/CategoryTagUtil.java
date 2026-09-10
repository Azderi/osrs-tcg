package com.osrstcg.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
/**
 * Helpers for parsing and normalizing category/region tags, which may be compound values joined
 * with {@code &} (e.g. {@code "Bosses & Slayer"}).
 */
public final class CategoryTagUtil
{
	private CategoryTagUtil()
	{
	}
/** Splits {@code raw} on {@code &} into its trimmed, non-empty parts; empty list if {@code raw} is null/blank. */
	public static List<String> expandCompoundParts(String raw)
	{
		if (raw == null)
		{
			return Collections.emptyList();
		}
		String s = raw.trim();
		if (s.isEmpty())
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>(4);
		for (String piece : s.split("&"))
		{
			String t = piece.trim();
			if (!t.isEmpty())
			{
				out.add(t);
			}
		}
		return out;
	}
/** Lowercased, trimmed form of a single (non-compound) tag part, used as a comparison/lookup key; empty string if null. */
	public static String canonicalKey(String singleTagPart)
	{
		if (singleTagPart == null)
		{
			return "";
		}
		return singleTagPart.trim().toLowerCase(Locale.ROOT);
	}
}

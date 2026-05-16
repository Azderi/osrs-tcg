package com.osrstcg.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Normalizes category strings for matching and UI: splits {@code &} compounds, case-fold keys, title-cased labels.
 */
public final class CategoryTagUtil
{
	private CategoryTagUtil()
	{
	}

	/**
	 * Splits a raw tag on {@code '&'} into trimmed non-empty pieces (e.g. {@code "A&B"} → {@code A}, {@code B}).
	 */
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

	public static String canonicalKey(String singleTagPart)
	{
		if (singleTagPart == null)
		{
			return "";
		}
		return singleTagPart.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Each whitespace-separated word: first character upper case, remainder lower case ({@link Locale#ROOT}).
	 */
	public static String toDisplayLabel(String canonicalKey)
	{
		if (canonicalKey == null || canonicalKey.isEmpty())
		{
			return "";
		}
		String[] words = canonicalKey.split("\\s+");
		StringBuilder sb = new StringBuilder();
		for (String w : words)
		{
			if (w.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(w.charAt(0)));
			if (w.length() > 1)
			{
				sb.append(w.substring(1).toLowerCase(Locale.ROOT));
			}
		}
		return sb.length() == 0 ? canonicalKey : sb.toString();
	}
}

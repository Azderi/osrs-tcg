package com.osrstcg.util;

import java.nio.charset.StandardCharsets;

/** Builds Old School RuneScape Wiki article URLs from a page title. */
public final class OsrsWiki
{
	private static final String WIKI_BASE = "https://oldschool.runescape.wiki/w/";

	/** No instances. */
	private OsrsWiki()
	{
	}

	/**
	 * Builds the wiki URL for {@code page}: spaces become underscores, {@code /} is kept
	 * literal (wiki subpage separator), and every other non-URL-safe character is percent-encoded.
	 * @return {@code null} if {@code page} is {@code null} or blank after trimming.
	 */
	public static String url(String page)
	{
		if (page == null)
		{
			return null;
		}
		String title = page.trim().replace(' ', '_');
		if (title.isEmpty())
		{
			return null;
		}
		StringBuilder encoded = new StringBuilder(title.length() + 16);
		for (int i = 0; i < title.length(); )
		{
			int cp = title.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '/')
			{
				encoded.append('/');
			}
			else if (isEncodeUriComponentSafe(cp))
			{
				encoded.appendCodePoint(cp);
			}
			else
			{
				byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
				for (byte b : bytes)
				{
					encoded.append('%');
					encoded.append(String.format("%02X", b & 0xFF));
				}
			}
		}
		return WIKI_BASE + encoded;
	}

	/** @return true if {@code cp} does not need percent-encoding (mirrors JS {@code encodeURIComponent} safe set). */
	private static boolean isEncodeUriComponentSafe(int cp)
	{
		return (cp >= 'A' && cp <= 'Z')
			|| (cp >= 'a' && cp <= 'z')
			|| (cp >= '0' && cp <= '9')
			|| cp == '-' || cp == '_' || cp == '.' || cp == '!' || cp == '~'
			|| cp == '*' || cp == '\'' || cp == '(' || cp == ')';
	}
}

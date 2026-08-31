package com.osrstcg.util;

import java.nio.charset.StandardCharsets;

public final class OsrsWiki
{
	private static final String WIKI_BASE = "https://oldschool.runescape.wiki/w/";

	private OsrsWiki()
	{
	}

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

	private static boolean isEncodeUriComponentSafe(int cp)
	{
		return (cp >= 'A' && cp <= 'Z')
			|| (cp >= 'a' && cp <= 'z')
			|| (cp >= '0' && cp <= '9')
			|| cp == '-' || cp == '_' || cp == '.' || cp == '!' || cp == '~'
			|| cp == '*' || cp == '\'' || cp == '(' || cp == ')';
	}
}

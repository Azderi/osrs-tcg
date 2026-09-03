package com.osrstcg.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decode common HTML character references for catalog display strings. */
public final class HtmlEntities
{
	private static final Pattern DECIMAL_REF = Pattern.compile("&#(\\d+);");
	private static final Pattern HEX_REF = Pattern.compile("&#x([0-9a-fA-F]+);", Pattern.CASE_INSENSITIVE);

	/** No instances. */
	private HtmlEntities()
	{
	}

	/**
	 * Decodes {@code &amp;}/{@code &lt;}/{@code &gt;}/{@code &quot;}/{@code &apos;} and numeric
	 * (decimal and hex) character references in {@code value}. Returns {@code value} unchanged if
	 * it's {@code null}, empty, or contains no {@code &}.
	 */
	public static String decode(String value)
	{
		if (value == null || value.isEmpty() || value.indexOf('&') < 0)
		{
			return value;
		}
		String s = value;
		s = s.replace("&amp;", "&");
		s = s.replace("&lt;", "<");
		s = s.replace("&gt;", ">");
		s = s.replace("&quot;", "\"");
		s = s.replace("&apos;", "'");
		s = replaceRefs(s, DECIMAL_REF, 10);
		s = replaceRefs(s, HEX_REF, 16);
		return s;
	}

	/** Replaces every match of {@code pattern} (a numeric char-ref pattern) with the character it encodes, parsed in {@code radix}. */
	private static String replaceRefs(String input, Pattern pattern, int radix)
	{
		Matcher matcher = pattern.matcher(input);
		if (!matcher.find())
		{
			return input;
		}
		StringBuffer out = new StringBuffer();
		do
		{
			int codePoint = Integer.parseInt(matcher.group(1), radix);
			matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) codePoint)));
		}
		while (matcher.find());
		matcher.appendTail(out);
		return out.toString();
	}
}

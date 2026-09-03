package com.osrstcg.util;

import java.util.Locale;

/** Formats numbers for display: thousands-space-separated, or compact with k/M suffixes. */
public final class NumberFormatting
{
	/** No instances. */
	private NumberFormatting()
	{
	}

	/** @return {@code value} with a space every three digits, e.g. {@code "1 234 567"}. */
	public static String format(long value)
	{
		return formatWithSpaces(value);
	}

	/** @return space-grouped {@code value}, or {@code "-"} if {@code value} is {@code null}. */
	public static String format(Long value)
	{
		return value == null ? "-" : formatWithSpaces(value);
	}

	/** @return {@code value} with a space every three digits. */
	public static String format(int value)
	{
		return formatWithSpaces((long) value);
	}

	/**
	 * Compact form: {@code "1.2M"} at or above one million, {@code "123k"} at or above 100,000,
	 * otherwise the same space-grouped format as {@link #format(long)}.
	 */
	public static String formatCompact(long value)
	{
		long abs = Math.abs(value);
		String sign = value < 0 ? "-" : "";
		if (abs >= 1_000_000L)
		{
			double millions = abs / 1_000_000d;
			return sign + String.format(Locale.US, "%.1fM", millions);
		}
		if (abs >= 100_000L)
		{
			return sign + (abs / 1000L) + "k";
		}
		return formatWithSpaces(value);
	}

	/** @return {@code value} as decimal digits with a space inserted every three digits from the right; keeps the sign. */
	private static String formatWithSpaces(long value)
	{
		String sign = value < 0 ? "-" : "";
		String digits = Long.toString(Math.abs(value));
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < digits.length(); i++)
		{
			if (i > 0 && ((digits.length() - i) % 3 == 0))
			{
				out.append(' ');
			}
			out.append(digits.charAt(i));
		}
		return sign + out;
	}
}

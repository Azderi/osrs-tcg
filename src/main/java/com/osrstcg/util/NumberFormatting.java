package com.osrstcg.util;

import java.util.Locale;

public final class NumberFormatting
{
	private NumberFormatting()
	{
	}

	public static String format(long value)
	{
		return formatWithSpaces(value);
	}

	public static String format(Long value)
	{
		return value == null ? "-" : formatWithSpaces(value);
	}

	public static String format(int value)
	{
		return formatWithSpaces((long) value);
	}

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

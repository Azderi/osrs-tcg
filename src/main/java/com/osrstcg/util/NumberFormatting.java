package com.runelitetcg.util;

/**
 * Consistent display: group thousands with a space (e.g. {@code 1 234 567}).
 */
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

package com.osrstcg.cloud.api;

import com.google.gson.JsonObject;

/** Shared Gson field readers for cloud JSON payloads. */
public final class JsonObjects
{
	private JsonObjects()
	{
	}

	public static String text(JsonObject o, String key)
	{
		if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull())
		{
			return null;
		}
		try
		{
			return o.get(key).getAsString();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** {@link #text} then trim; blank becomes {@code null}. */
	public static String textTrimmed(JsonObject o, String key)
	{
		String value = text(o, key);
		if (value == null)
		{
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public static int readInt(JsonObject o, String key)
	{
		return (int) Math.round(readDouble(o, key));
	}

	public static long readLong(JsonObject o, String key)
	{
		return Math.round(readDouble(o, key));
	}

	public static double readDouble(JsonObject o, String key)
	{
		Double value = readNumber(o, key);
		return value == null ? 0.0d : value;
	}

	public static Double readNumber(JsonObject o, String primary, String... aliases)
	{
		Double value = readNumberKey(o, primary);
		if (value != null)
		{
			return value;
		}
		if (aliases == null)
		{
			return null;
		}
		for (String alias : aliases)
		{
			value = readNumberKey(o, alias);
			if (value != null)
			{
				return value;
			}
		}
		return null;
	}

	private static Double readNumberKey(JsonObject o, String key)
	{
		if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull())
		{
			return null;
		}
		try
		{
			return o.get(key).getAsDouble();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}

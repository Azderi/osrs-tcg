package com.osrstcg.cloud.api;

import com.google.gson.JsonObject;

public final class JsonObjects
{
	private JsonObjects()
	{
	}

	public static String blankToNull(String value)
	{
		if (value == null || value.isBlank())
		{
			return null;
		}
		return value.trim();
	}

	public static JsonObject objectOrEmpty(JsonObject root, String key)
	{
		if (root != null && key != null && root.has(key) && root.get(key).isJsonObject())
		{
			return root.getAsJsonObject(key);
		}
		return new JsonObject();
	}

	public static boolean readBoolean(JsonObject o, String key)
	{
		if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull())
		{
			return false;
		}
		try
		{
			return o.get(key).getAsBoolean();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	public static Double readNullableDouble(JsonObject o, String key)
	{
		return readNumberKey(o, key);
	}

	public static long readLong(JsonObject o, String key, long fallback)
	{
		Double value = readNumberKey(o, key);
		return value == null ? fallback : Math.round(value);
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

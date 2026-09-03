package com.osrstcg.cloud.api;

import com.google.gson.JsonObject;

/** Null-safe accessors for reading fields out of cloud API {@link JsonObject} responses. */
public final class JsonObjects
{
	private JsonObjects()
	{
	}

	/** Trims {@code value}; returns null if it was null or blank. */
	public static String blankToNull(String value)
	{
		if (value == null || value.isBlank())
		{
			return null;
		}
		return value.trim();
	}

	/** Returns {@code root.get(key)} as an object, or an empty {@link JsonObject} if absent/null/not an object. */
	public static JsonObject objectOrEmpty(JsonObject root, String key)
	{
		if (root != null && key != null && root.has(key) && root.get(key).isJsonObject())
		{
			return root.getAsJsonObject(key);
		}
		return new JsonObject();
	}

	/** Reads a boolean field, defaulting to false when absent, null, or not boolean-typed. */
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

	/** Reads a numeric field as a nullable {@link Double}; null when absent, null, or not numeric. */
	public static Double readNullableDouble(JsonObject o, String key)
	{
		return readNumberKey(o, key);
	}

	/** Reads a numeric field rounded to a long, or {@code fallback} when absent/null/not numeric. */
	public static long readLong(JsonObject o, String key, long fallback)
	{
		Double value = readNumberKey(o, key);
		return value == null ? fallback : Math.round(value);
	}

	/** Reads a string field, or null when absent, null, or not string-typed. */
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

	/** Like {@link #text(JsonObject, String)}, but trims and returns null for an empty result. */
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

	/** Reads a numeric field rounded to an int, or 0 when absent/null/not numeric. */
	public static int readInt(JsonObject o, String key)
	{
		return (int) Math.round(readDouble(o, key));
	}

	/** Reads a numeric field rounded to a long, or 0 when absent/null/not numeric. */
	public static long readLong(JsonObject o, String key)
	{
		return Math.round(readDouble(o, key));
	}

	/** Reads a numeric field as a primitive double, or 0.0 when absent/null/not numeric. */
	public static double readDouble(JsonObject o, String key)
	{
		Double value = readNumber(o, key);
		return value == null ? 0.0d : value;
	}

	/** Reads a numeric field by {@code primary} key, falling back through {@code aliases} in order; null if none match. */
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

	/** Reads a numeric field as a nullable {@link Double}; null when absent, null, or not numeric. */
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

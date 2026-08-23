package com.osrstcg.catalog;

/** URL normalize / cache-key identity for card artwork fetches. */
final class ImageCacheIdentity
{
	private ImageCacheIdentity()
	{
	}

	/**
	 * Stable cache identity for an absolute URL. Strips rotating signed {@code token} query
	 * params (and any query on {@code /artwork/files/}) so the same artwork ULID maps to one
	 * disk/memory entry across pack opens.
	 */
	static String cacheIdentity(String absoluteUrl)
	{
		if (absoluteUrl == null || absoluteUrl.isEmpty())
		{
			return "";
		}
		String lower = absoluteUrl.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("/artwork/files/"))
		{
			int q = absoluteUrl.indexOf('?');
			return q >= 0 ? absoluteUrl.substring(0, q) : absoluteUrl;
		}
		return stripQueryParam(absoluteUrl, "token");
	}

	/** {@code true} when HTTP auth is ephemeral (fresh {@code token} on each pack payload). */
	static boolean isEphemeralAuthUrl(String absoluteUrl)
	{
		if (absoluteUrl == null || absoluteUrl.isEmpty())
		{
			return false;
		}
		String lower = absoluteUrl.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("/artwork/files/") && lower.contains("token=");
	}

	/**
	 * Removes a single query parameter (case-insensitive name) while preserving other params.
	 */
	static String stripQueryParam(String absoluteUrl, String paramName)
	{
		if (absoluteUrl == null || paramName == null || paramName.isEmpty())
		{
			return absoluteUrl == null ? "" : absoluteUrl;
		}
		int q = absoluteUrl.indexOf('?');
		if (q < 0)
		{
			return absoluteUrl;
		}
		String base = absoluteUrl.substring(0, q);
		String query = absoluteUrl.substring(q + 1);
		if (query.isEmpty())
		{
			return base;
		}
		StringBuilder kept = new StringBuilder();
		for (String part : query.split("&"))
		{
			if (part.isEmpty())
			{
				continue;
			}
			int eq = part.indexOf('=');
			String name = eq >= 0 ? part.substring(0, eq) : part;
			if (name.equalsIgnoreCase(paramName))
			{
				continue;
			}
			if (kept.length() > 0)
			{
				kept.append('&');
			}
			kept.append(part);
		}
		return kept.length() == 0 ? base : base + '?' + kept;
	}
}

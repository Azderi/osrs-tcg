package com.osrstcg.service;

/**
 * Shared osrs-tcg.xyz web share constants and helpers, used by both {@link CollectionShareService}
 * (publishing a player's own collection) and {@link GroupCollectionSyncService} (reading teammates'
 * published collections back for Group Ironman pooling).
 */
final class WebShareEndpoints
{
	static final String DEFAULT_PUBLIC_BASE = "https://osrs-tcg.xyz";
	static final String API_BASE = DEFAULT_PUBLIC_BASE + "/api/v1";

	private WebShareEndpoints()
	{
	}

	static String encodePathSegment(String value)
	{
		// Space → %20; keep letters/digits/_/- unescaped for readable OSRS names
		StringBuilder sb = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
				|| c == '_' || c == '-')
			{
				sb.append(c);
			}
			else if (c == ' ')
			{
				sb.append("%20");
			}
			else
			{
				sb.append('%');
				sb.append(String.format("%02X", (int) c));
			}
		}
		return sb.toString();
	}
}

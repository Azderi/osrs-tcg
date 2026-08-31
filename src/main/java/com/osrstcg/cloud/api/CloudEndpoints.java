package com.osrstcg.cloud.api;

/** Fixed production HTTPS endpoints. */
public final class CloudEndpoints
{
	public static final String API_BASE_URL = "https://api.osrs-tcg.net/api/v1";
	public static final String WEB_BASE_URL = "https://osrs-tcg.net";

	private CloudEndpoints()
	{
	}

	/** Join a path under {@link #API_BASE_URL}. Absolute {@code https://} URLs pass through. */
	public static String apiUrl(String pathAndQuery)
	{
		if (pathAndQuery == null || pathAndQuery.isBlank())
		{
			return API_BASE_URL;
		}
		String path = pathAndQuery.trim();
		if (path.startsWith("https://"))
		{
			return path;
		}
		if (!path.startsWith("/"))
		{
			path = "/" + path;
		}
		return API_BASE_URL + path;
	}

	/** Join a path under {@link #WEB_BASE_URL}. Absolute {@code https://} URLs pass through. */
	public static String webUrl(String pathOrUrl)
	{
		return joinHttps(WEB_BASE_URL, pathOrUrl);
	}

	public static String resolvePublicUrl(String pathOrUrl)
	{
		if (pathOrUrl == null)
		{
			return "";
		}
		String raw = pathOrUrl.trim();
		if (raw.isEmpty())
		{
			return "";
		}
		if (raw.startsWith("https://"))
		{
			return raw;
		}
		if (raw.equals("/api/v1") || raw.startsWith("/api/v1/") || raw.startsWith("/api/v1?"))
		{
			return apiUrl(raw.substring("/api/v1".length()));
		}
		if (raw.startsWith("/api/"))
		{
			return apiUrl(raw);
		}
		return webUrl(raw);
	}

	/**
	 * Rewrite an absolute {@code https://} URL onto {@link #WEB_BASE_URL} (keeps path + query),
	 * or join a relative path to the web base.
	 */
	public static String rewriteToWebBase(String serverUrl)
	{
		if (serverUrl == null || serverUrl.isBlank())
		{
			return null;
		}
		String raw = serverUrl.trim();
		if (raw.startsWith("https://"))
		{
			int pathStart = raw.indexOf('/', "https://".length());
			if (pathStart < 0)
			{
				return WEB_BASE_URL;
			}
			return WEB_BASE_URL + raw.substring(pathStart);
		}
		return WEB_BASE_URL + (raw.startsWith("/") ? raw : "/" + raw);
	}

	private static String joinHttps(String httpsBase, String pathOrUrl)
	{
		if (pathOrUrl == null)
		{
			return "";
		}
		String path = pathOrUrl.trim();
		if (path.isEmpty())
		{
			return "";
		}
		if (path.startsWith("https://"))
		{
			return path;
		}
		return httpsBase + (path.startsWith("/") ? path : "/" + path);
	}
}

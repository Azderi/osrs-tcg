package com.osrstcg.cloud.api;

/** Fixed production HTTPS endpoints. */
public final class CloudEndpoints
{
	public static final String API_BASE_URL = "https://api.osrs-tcg.net/api/v1";
	public static final String WEB_BASE_URL = "https://osrs-tcg.net";

	private static final String API_V1_PREFIX = "/api/v1";

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
		if (path.equals(API_V1_PREFIX))
		{
			return API_BASE_URL;
		}
		if (path.startsWith(API_V1_PREFIX + "/"))
		{
			path = path.substring(API_V1_PREFIX.length());
		}
		else if (path.startsWith(API_V1_PREFIX + "?"))
		{
			path = path.substring(API_V1_PREFIX.length());
		}
		return API_BASE_URL + path;
	}

	/** Join a path under {@link #WEB_BASE_URL}. Absolute {@code https://} URLs pass through. */
	public static String webUrl(String pathOrUrl)
	{
		return joinHttps(WEB_BASE_URL, pathOrUrl);
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

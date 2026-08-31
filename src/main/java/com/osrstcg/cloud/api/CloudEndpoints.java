package com.osrstcg.cloud.api;

public final class CloudEndpoints
{
	public static final String API_BASE_URL = "https://api.osrs-tcg.net/api/v1";

	public static final String WEB_BASE_URL = "https://osrs-tcg.net";

	private static final String API_V1_PREFIX = "/api/v1";

	private CloudEndpoints()
	{
	}

	public static String apiUrl(String pathAndQuery)
	{
		if (pathAndQuery == null || pathAndQuery.isBlank())
		{
			return API_BASE_URL;
		}
		String path = pathAndQuery.trim();
		if (path.startsWith("http://") || path.startsWith("https://"))
		{
			return path;
		}
		if (path.startsWith("//"))
		{
			return "https:" + path;
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
			path = "/" + path.substring(API_V1_PREFIX.length());
		}
		return trimTrailingSlash(API_BASE_URL) + path;
	}

	public static String webUrl(String pathOrUrl)
	{
		return resolvePublicUrl(WEB_BASE_URL, pathOrUrl);
	}

	public static String rewriteToWebBase(String serverUrl)
	{
		if (serverUrl == null || serverUrl.isBlank())
		{
			return null;
		}
		String root = trimTrailingSlash(WEB_BASE_URL);
		String raw = serverUrl.trim();
		try
		{
			java.net.URI uri = java.net.URI.create(raw);
			if (uri.isAbsolute())
			{
				String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
				String query = uri.getRawQuery();
				return root + path + (query == null || query.isEmpty() ? "" : "?" + query);
			}
		}
		catch (IllegalArgumentException ignored)
		{
			// fall through to relative join
		}
		return root + (raw.startsWith("/") ? raw : "/" + raw);
	}

	public static String resolvePublicUrl(String baseUrl, String pathOrUrl)
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
		if (path.startsWith("http://") || path.startsWith("https://"))
		{
			return path;
		}
		if (path.startsWith("//"))
		{
			return "https:" + path;
		}
		String root = trimTrailingSlash(baseUrl);
		if (root.isEmpty())
		{
			return path.startsWith("/") ? path : "/" + path;
		}
		return root + (path.startsWith("/") ? path : "/" + path);
	}

	public static String trimTrailingSlash(String url)
	{
		if (url == null)
		{
			return "";
		}
		String t = url.trim();
		while (t.endsWith("/"))
		{
			t = t.substring(0, t.length() - 1);
		}
		return t;
	}
}

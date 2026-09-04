package com.osrstcg.cloud.api;
/** Base URLs for the cloud API/web backend and helpers for building/rewriting URLs against them. */
public final class CloudEndpoints
{
	public static final String API_BASE_URL = "https://api.osrs-tcg.net/api/v1";
	public static final String WEB_BASE_URL = "https://osrs-tcg.net";

	private CloudEndpoints()
	{
	}
/** Resolves a path (or already-absolute {@code https://} URL) against {@link #API_BASE_URL}. */
	public static String apiUrl(String pathAndQuery)
	{
		if (pathAndQuery == null || pathAndQuery.isBlank())
		{
			return API_BASE_URL;
		}
		String joined = joinHttps(API_BASE_URL, pathAndQuery);
		return joined.isEmpty() ? API_BASE_URL : joined;
	}
/** Resolves a path (or already-absolute {@code https://} URL) against {@link #WEB_BASE_URL}. */
	public static String webUrl(String pathOrUrl)
	{
		return joinHttps(WEB_BASE_URL, pathOrUrl);
	}
/**
	 * Resolves a value that may be an absolute URL, an {@code /api/v1/...} path, another
	 * {@code /api/...} path, or a bare web path, into an absolute {@code https://} URL.
	 * Returns {@code ""} for null/blank input.
	 */
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
	 * Rewrites a server-provided URL (any host) or path onto {@link #WEB_BASE_URL}, keeping only
	 * the path/query. Returns null for null/blank input.
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
/** Joins {@code pathOrUrl} onto {@code httpsBase} unless it is already an absolute {@code https://} URL. */
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

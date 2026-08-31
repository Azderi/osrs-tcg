package com.osrstcg.catalog;

import com.osrstcg.persist.TcgStateHash;
import java.util.Locale;
import okhttp3.HttpUrl;

final class ImageCacheIdentity
{
	static final int MAX_MEMORY_IMAGE_EDGE_PX = 130;
	static final int MAX_MEMORY_FULL_ART_EDGE_PX = 520;
	static final int MAX_MEMORY_PACK_SLEEVE_EDGE_PX = 1100;

	private ImageCacheIdentity()
	{
	}

	static String cacheIdentity(String absoluteUrl)
	{
		if (absoluteUrl == null || absoluteUrl.isEmpty())
		{
			return "";
		}
		String lower = absoluteUrl.toLowerCase(Locale.ROOT);
		if (lower.contains("/artwork/files/"))
		{
			int q = absoluteUrl.indexOf('?');
			return q >= 0 ? absoluteUrl.substring(0, q) : absoluteUrl;
		}
		return stripQueryParam(absoluteUrl, "token");
	}

	static boolean isEphemeralAuthUrl(String absoluteUrl)
	{
		if (absoluteUrl == null || absoluteUrl.isEmpty())
		{
			return false;
		}
		String lower = absoluteUrl.toLowerCase(Locale.ROOT);
		return lower.contains("/artwork/files/") && lower.contains("token=");
	}

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

	static int maxMemoryEdgeForUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return MAX_MEMORY_IMAGE_EDGE_PX;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		if (lower.contains("/images/cardback"))
		{
			return MAX_MEMORY_FULL_ART_EDGE_PX;
		}
		if (lower.contains("/images/packs/"))
		{
			if (lower.contains("thumbnail"))
			{
				return MAX_MEMORY_IMAGE_EDGE_PX;
			}
			return MAX_MEMORY_PACK_SLEEVE_EDGE_PX;
		}
		if (lower.contains("/artwork/files/") || lower.contains("/foil/"))
		{
			return MAX_MEMORY_FULL_ART_EDGE_PX;
		}
		return MAX_MEMORY_IMAGE_EDGE_PX;
	}

	static boolean isPackAssetUrl(String normalizedUrl)
	{
		return normalizedUrl != null
			&& normalizedUrl.toLowerCase(Locale.ROOT).contains("/images/packs/");
	}

	static boolean isCardBackUrl(String normalizedUrl)
	{
		return normalizedUrl != null
			&& normalizedUrl.toLowerCase(Locale.ROOT).contains("/images/cardback");
	}

	static boolean isShortFailCooldownUrl(String normalizedUrl)
	{
		return isPackAssetUrl(normalizedUrl) || isCardBackUrl(normalizedUrl);
	}

	static String packDiskFileName(String normalizedUrl)
	{
		if (isCardBackUrl(normalizedUrl))
		{
			return "cardback.png";
		}
		if (!isPackAssetUrl(normalizedUrl))
		{
			return null;
		}
		String path = normalizedUrl;
		int q = path.indexOf('?');
		if (q >= 0)
		{
			path = path.substring(0, q);
		}
		int slash = path.lastIndexOf('/');
		String raw = slash >= 0 ? path.substring(slash + 1) : path;
		if (raw.isBlank())
		{
			return TcgStateHash.hexOfUtf8(normalizedUrl) + ".bin";
		}
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++)
		{
			char c = raw.charAt(i);
			if ((c >= 'a' && c <= 'z')
				|| (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9')
				|| c == '.' || c == '_' || c == '-')
			{
				sb.append(c);
			}
			else
			{
				sb.append('_');
			}
		}
		String cleaned = sb.toString();
		if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals(".."))
		{
			return TcgStateHash.hexOfUtf8(normalizedUrl) + ".bin";
		}
		return cleaned;
	}

	static boolean isOsrsTcgNetUrl(String url)
	{
		HttpUrl parsed = HttpUrl.parse(url);
		if (parsed == null)
		{
			return false;
		}
		String host = parsed.host();
		if (host == null || host.isEmpty())
		{
			return false;
		}
		String lower = host.toLowerCase(Locale.ROOT);
		return "osrs-tcg.net".equals(lower) || lower.endsWith(".osrs-tcg.net");
	}
}

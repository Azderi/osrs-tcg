package com.osrstcg.catalog;

import com.osrstcg.persist.TcgStateHash;
import java.util.Locale;
import okhttp3.HttpUrl;
/**
 * URL classification and normalization helpers for {@link CardImageCacheService}: strips volatile
 * query params to derive a stable cache key, and picks decode-size caps and disk filenames by URL shape.
 */
final class ImageCacheIdentity
{
	static final int MAX_MEMORY_IMAGE_EDGE_PX = 130;
	static final int MAX_MEMORY_FULL_ART_EDGE_PX = 520;
	static final int MAX_MEMORY_PACK_SLEEVE_EDGE_PX = 1100;

	private ImageCacheIdentity()
	{
	}
/** Stable cache key for {@code absoluteUrl}: strips all query params for artwork-file paths (their auth tokens vary per request), else strips only the {@code token} query param. */
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
/** Whether {@code absoluteUrl} is an artwork-file URL carrying a short-lived auth token, meaning fetch failures shouldn't be cached as a cooldown. */
	static boolean isEphemeralAuthUrl(String absoluteUrl)
	{
		if (absoluteUrl == null || absoluteUrl.isEmpty())
		{
			return false;
		}
		String lower = absoluteUrl.toLowerCase(Locale.ROOT);
		return lower.contains("/artwork/files/") && lower.contains("token=");
	}
/** Removes every occurrence of the query param named {@code paramName} from {@code absoluteUrl}, dropping the {@code ?} entirely if nothing remains. */
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
/** Max decoded-image edge length (px) to keep in memory for {@code url}: larger for card backs, full-art/foil, and pack sleeves (thumbnails excepted); default otherwise. */
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
/** Whether {@code normalizedUrl} points at a pack sleeve/thumbnail asset. */
	static boolean isPackAssetUrl(String normalizedUrl)
	{
		return normalizedUrl != null
			&& normalizedUrl.toLowerCase(Locale.ROOT).contains("/images/packs/");
	}
/** Whether {@code normalizedUrl} points at the card-back image. */
	static boolean isCardBackUrl(String normalizedUrl)
	{
		return normalizedUrl != null
			&& normalizedUrl.toLowerCase(Locale.ROOT).contains("/images/cardback");
	}
/** Whether {@code normalizedUrl} should use the shorter fetch-failure cooldown (pack/card-back assets, expected to be more transiently unavailable). */
	static boolean isShortFailCooldownUrl(String normalizedUrl)
	{
		return isPackAssetUrl(normalizedUrl) || isCardBackUrl(normalizedUrl);
	}
/**
	 * Disk cache filename for a pack asset URL, or {@code null} if {@code normalizedUrl} isn't a pack
	 * asset/card-back URL. Card backs always use {@code "cardback.png"}; other pack assets keep the
	 * URL's last path segment with unsafe characters replaced by {@code _}, falling back to a hash
	 * of the URL if that segment is blank or unusable.
	 */
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
/** Whether {@code url}'s host is {@code osrs-tcg.net} or a subdomain of it. */
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

package com.osrstcg.cloud.catalog;

/**
 * Helpers for pack art paths from {@code GET /packs} ({@code thumbnail} / {@code image}).
 * Hosted assets live on the website under {@code /images/packs/...}.
 * Shop tiles use {@code thumbnail}; the pack-opening overlay must use {@code image}.
 */
public final class PackImageUrls
{
	private PackImageUrls()
	{
	}

	/** Web-relative or absolute URL suitable for {@link com.osrstcg.catalog.CardImageCacheService}. */
	public static boolean isHostedPath(String path)
	{
		if (path == null || path.isBlank())
		{
			return false;
		}
		String t = path.trim();
		return t.startsWith("/") || t.startsWith("https://");
	}

	/** Full-resolution sleeve for the pack-opening overlay ({@link com.osrstcg.catalog.BoosterPackDefinition#getImage()}). */
	public static String revealSleevePath(com.osrstcg.catalog.BoosterPackDefinition pack)
	{
		if (pack == null)
		{
			return null;
		}
		String image = pack.getImage();
		return isHostedPath(image) ? image.trim() : null;
	}
}

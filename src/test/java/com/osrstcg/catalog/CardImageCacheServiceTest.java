package com.osrstcg.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CardImageCacheServiceTest
{
	@Test
	public void cacheIdentityStripsArtworkTokenQuery()
	{
		String a = "https://api.osrs-tcg.net/api/v1/artwork/files/01HZTESTULIDTOKEN0000000001?token=exp.sigA";
		String b = "https://api.osrs-tcg.net/api/v1/artwork/files/01HZTESTULIDTOKEN0000000001?token=exp.sigB";
		assertEquals(
			"https://api.osrs-tcg.net/api/v1/artwork/files/01HZTESTULIDTOKEN0000000001",
			ImageCacheIdentity.cacheIdentity(a));
		assertEquals(ImageCacheIdentity.cacheIdentity(a), ImageCacheIdentity.cacheIdentity(b));
	}

	@Test
	public void cacheIdentityKeepsStableDetailUrls()
	{
		String url = "https://osrs-tcg.net/images/items/detail/1234.png";
		assertEquals(url, ImageCacheIdentity.cacheIdentity(url));
	}

	@Test
	public void stripQueryParamPreservesOtherParams()
	{
		String url = "https://example.test/img.png?foo=1&token=abc&bar=2";
		assertEquals(
			"https://example.test/img.png?foo=1&bar=2",
			ImageCacheIdentity.stripQueryParam(url, "token"));
	}

	@Test
	public void ephemeralAuthDetectedForSignedArtwork()
	{
		assertTrue(ImageCacheIdentity.isEphemeralAuthUrl(
			"https://api.osrs-tcg.net/api/v1/artwork/files/01HZ?token=x.y"));
		assertFalse(ImageCacheIdentity.isEphemeralAuthUrl(
			"https://osrs-tcg.net/images/items/detail/1.png"));
	}

	@Test
	public void packAssetUrlDetected()
	{
		assertTrue(ImageCacheIdentity.isPackAssetUrl(
			"https://osrs-tcg.net/images/packs/Pack_Clue_thumbnail.png"));
		assertTrue(ImageCacheIdentity.isPackAssetUrl(
			"https://osrs-tcg.net/images/packs/Pack_Standard.png"));
		assertFalse(ImageCacheIdentity.isPackAssetUrl(
			"https://osrs-tcg.net/images/items/detail/1.png"));
	}

	@Test
	public void packDiskFileNameUsesBasename()
	{
		assertEquals(
			"Pack_Misthalin_thumbnail.png",
			ImageCacheIdentity.packDiskFileName(
				"https://osrs-tcg.net/images/packs/Pack_Misthalin_thumbnail.png"));
		assertEquals(
			"Pack_Clue_thumbnail.png",
			ImageCacheIdentity.packDiskFileName(
				"https://osrs-tcg.net/images/packs/Pack_Clue_thumbnail.png"));
		assertEquals(
			"cardback.png",
			ImageCacheIdentity.packDiskFileName(
				"https://osrs-tcg.net/images/Cardback_new.png"));
	}

	@Test
	public void cardBackUsesFullArtMemoryEdge()
	{
		assertEquals(
			520,
			ImageCacheIdentity.maxMemoryEdgeForUrl(
				"https://osrs-tcg.net/images/Cardback_new.png"));
	}

	@Test
	public void packThumbnailUsesSmallMemoryEdge()
	{
		assertEquals(
			130,
			ImageCacheIdentity.maxMemoryEdgeForUrl(
				"https://osrs-tcg.net/images/packs/Pack_Clue_thumbnail.png"));
		assertEquals(
			1100,
			ImageCacheIdentity.maxMemoryEdgeForUrl(
				"https://osrs-tcg.net/images/packs/Pack_Standard.png"));
	}
}

package com.osrstcg.ui;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.ui.card.CardFaceDrawRequest;
import com.osrstcg.ui.card.FoilFx;
import com.osrstcg.ui.card.WearFx;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** LRU of rasterized card faces used by {@link SharedCardRenderer}. */
final class CardFaceCache
{
	private static final int FACE_CACHE_MAX = 48;
	/** Access-ordered map so {@code removeEldestEntry} evicts the least-recently-used face once the cap is exceeded. */
	private static final Map<String, BufferedImage> FACE_CACHE = Collections.synchronizedMap(
		new LinkedHashMap<String, BufferedImage>(32, 0.75f, true)
		{
			/** Evicts the eldest entry once the cache exceeds {@link #FACE_CACHE_MAX}. */
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
			{
				return size() > FACE_CACHE_MAX;
			}
		});

	/** Not instantiated; all members are static. */
	private CardFaceCache()
	{
	}

	/**
	 * Returns the cached face for the given request, rendering and caching it via
	 * {@link SharedCardRenderer#renderFace} on a miss. Returns {@code null} if art is expected but not
	 * yet loaded (see {@link #expectsArtButMissing}).
	 */
	static BufferedImage cachedFace(int w, int h, CardFaceDrawRequest req)
	{
		if (expectsArtButMissing(req))
		{
			return null;
		}
		String key = cacheKey(w, h, req);
		BufferedImage cached = FACE_CACHE.get(key);
		if (cached != null)
		{
			return cached;
		}
		BufferedImage face = SharedCardRenderer.renderFace(w, h, req);
		FACE_CACHE.put(key, face);
		return face;
	}

	/** Returns whether a rendered face is already cached for this request, without rendering it. */
	static boolean contains(int w, int h, CardFaceDrawRequest req)
	{
		return FACE_CACHE.containsKey(cacheKey(w, h, req));
	}

	/** Returns the cached face for this request, or {@code null} on a miss (never renders). */
	static BufferedImage getIfPresent(int w, int h, CardFaceDrawRequest req)
	{
		return FACE_CACHE.get(cacheKey(w, h, req));
	}

	/**
	 * Cache-key fragment distinguishing art variants: the art key plus a pending marker or the art
	 * image's identity hash, so a request whose art hasn't loaded yet doesn't collide with one that has.
	 */
	private static String artIdentity(CardFaceDrawRequest req)
	{
		String artKey = req.getArtKey();
		if (artKey != null && !artKey.isEmpty())
		{
			if (req.getArt() == null)
			{
				return artKey + "|pending";
			}
			return artKey + "|" + Integer.toHexString(System.identityHashCode(req.getArt()));
		}
		return req.getArt() == null ? "0" : Integer.toHexString(System.identityHashCode(req.getArt()));
	}

	/** Returns true when the request names an art key but the art image hasn't been loaded yet. */
	static boolean expectsArtButMissing(CardFaceDrawRequest req)
	{
		if (req == null)
		{
			return false;
		}
		String artKey = req.getArtKey();
		return artKey != null && !artKey.isEmpty() && req.getArt() == null;
	}

	/** Builds the cache key from every visual input that affects the rendered face's pixels. */
	static String cacheKey(int w, int h, CardFaceDrawRequest req)
	{
		CardDefinition card = req.getCard();
		WearFx wear = req.getWear();
		FoilFx foilFx = req.getFoilFx();
		return w + "x" + h
			+ '|' + (card == null ? "" : card.getName())
			+ '|' + (card == null ? "" : card.getExamine())
			+ '|' + (card == null ? "" : card.getFoilImagePath())
			+ '|' + (req.isFoil() ? 1 : 0)
			+ '|' + (req.isFullArt() ? 1 : 0)
			+ '|' + req.getRarityColor().getRGB()
			+ '|' + (req.getTierLabel() == null ? "" : req.getTierLabel())
			+ '|' + SharedCardRenderer.scoreText(req)
			+ '|' + artIdentity(req)
			+ '|' + (wear == null ? "-" : wear.getGrade() + ":" + wear.getSeed())
			+ '|' + (foilFx == null ? "-" : String.valueOf(foilFx.getSeed()));
	}
}

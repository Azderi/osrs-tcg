package com.osrstcg.util;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/** Bounds for the collection album frame; persisted size is clamped to these limits. */
public final class CollectionAlbumWindowSizeUtil
{
	public static final int MIN_WIDTH = 1300;
	public static final int MIN_HEIGHT = 810;

	private CollectionAlbumWindowSizeUtil()
	{
	}

	public static boolean isStoredSize(int width, int height)
	{
		return width > 0 && height > 0;
	}

	public static Dimension resolve(int storedWidth, int storedHeight)
	{
		if (!isStoredSize(storedWidth, storedHeight))
		{
			return new Dimension(MIN_WIDTH, MIN_HEIGHT);
		}
		return clamp(storedWidth, storedHeight);
	}

	public static Dimension clamp(int width, int height)
	{
		int w = Math.max(MIN_WIDTH, width);
		int h = Math.max(MIN_HEIGHT, height);
		Rectangle max = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		if (max != null)
		{
			w = Math.min(w, max.width);
			h = Math.min(h, max.height);
		}
		return new Dimension(w, h);
	}
}

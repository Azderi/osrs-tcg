package com.osrstcg.ui;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** Shared letterbox-fit and inset geometry for card/pack image layout. */
public final class ImageLayout
{
	private ImageLayout()
	{
	}

	/** Shrinks {@code r} by {@code pad} on all sides; width/height clamped to at least 1. */
	public static Rectangle inset(Rectangle r, int pad)
	{
		return new Rectangle(r.x + pad, r.y + pad, Math.max(1, r.width - pad * 2), Math.max(1, r.height - pad * 2));
	}

	/**
	 * Largest rect that fits {@code image} inside {@code bounds} (aspect preserved), centered.
	 * Null/degenerate image returns a copy of {@code bounds}.
	 */
	public static Rectangle fitCenteredRect(Rectangle bounds, BufferedImage image)
	{
		if (image == null)
		{
			return new Rectangle(bounds);
		}
		int sw = image.getWidth();
		int sh = image.getHeight();
		if (sw <= 0 || sh <= 0)
		{
			return new Rectangle(bounds);
		}
		double ratio = Math.min((double) bounds.width / (double) sw, (double) bounds.height / (double) sh);
		int w = Math.max(1, (int) Math.round(sw * ratio));
		int h = Math.max(1, (int) Math.round(sh * ratio));
		return new Rectangle(
			bounds.x + (bounds.width - w) / 2,
			bounds.y + (bounds.height - h) / 2,
			w,
			h);
	}
}

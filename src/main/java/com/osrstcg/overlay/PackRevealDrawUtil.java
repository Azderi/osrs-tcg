package com.osrstcg.overlay;

import com.osrstcg.ui.SharedCardRenderer;
import com.osrstcg.ui.card.CardColorMath;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.client.ui.FontManager;

/**
 * Stateless drawing helpers for the pack reveal overlay: the close button, glow effects, badges/labels,
 * and image/rect fitting math. Called from overlay rendering ({@code Graphics2D}) on RuneLite's overlay
 * render thread; the glow bake cache is synchronized since it may be read/written across renders.
 */
final class PackRevealDrawUtil
{
	static final int CLOSE_BUTTON_SIZE = 26;
	private static final Color CLOSE_BG_TOP = new Color(0x2E, 0x2E, 0x2E, 248);
	private static final Color CLOSE_BG_BOTTOM = new Color(0x10, 0x10, 0x10, 248);
	private static final Color CLOSE_BORDER = new Color(255, 255, 255, 42);
	private static final Color CLOSE_BORDER_HOVER = new Color(0xFF, 0xF5, 0xDC, 110);
	private static final Color CLOSE_INSET_HIGHLIGHT = new Color(255, 255, 255, 38);
	private static final Color CLOSE_ICON = new Color(0xE0, 0x4B, 0x4B);
	private static final Color CLOSE_ICON_HOVER = new Color(0xFF, 0x6B, 0x6B);
	private static final Color CLOSE_HOVER_WASH = new Color(255, 255, 255, 24);
	private static final Color CLOSE_SHADOW = new Color(0, 0, 0, 150);
	private static final int CLOSE_RADIUS = 5;

	private static final int GLOW_CACHE_MAX = 24;
	private static final int GLOW_LAYERS = 6;
	private static final float GLOW_LAYER_ALPHA = 0.58f;
	private static final float GLOW_MAX_EXPAND = 28f;
	private static final Map<String, BufferedImage> GLOW_CACHE = Collections.synchronizedMap(
		new LinkedHashMap<String, BufferedImage>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
			{
				return size() > GLOW_CACHE_MAX;
			}
		});

	/** No instances; all members are static. */
	private PackRevealDrawUtil()
	{
	}

	/** Fills {@code canvas} with a semi-transparent black wash to dim the game view behind the overlay. */
	static void drawDim(Graphics2D g, Rectangle canvas)
	{
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		g.setColor(Color.BLACK);
		g.fillRect(canvas.x, canvas.y, canvas.width, canvas.height);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
	}

	/** Writes the close button's bounds (top-right corner of {@code canvas}, edge-padded) into {@code outBounds}. */
	static void layoutCloseButton(Rectangle canvas, Rectangle outBounds)
	{
		int pad = PackRevealLayout.VIEWPORT_EDGE_PAD;
		int size = CLOSE_BUTTON_SIZE;
		outBounds.setBounds(
			canvas.x + canvas.width - pad - size,
			canvas.y + pad,
			size,
			size);
	}

	/** Draws the rounded close button (shadow, gradient panel, inset highlight, border, X icon) with hover styling. */
	static void drawCloseButton(Graphics2D g, Rectangle bounds, boolean hover)
	{
		if (g == null || bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			float r = CLOSE_RADIUS * 2f;
			int shadowOffset = 2;
			RoundRectangle2D shadow = new RoundRectangle2D.Float(
				bounds.x + shadowOffset,
				bounds.y + shadowOffset,
				bounds.width,
				bounds.height,
				r,
				r);
			g2.setColor(CLOSE_SHADOW);
			g2.fill(shadow);

			RoundRectangle2D panel = new RoundRectangle2D.Float(
				bounds.x, bounds.y, bounds.width, bounds.height, r, r);
			g2.setPaint(new GradientPaint(
				bounds.x,
				bounds.y,
				CLOSE_BG_TOP,
				bounds.x,
				bounds.y + bounds.height,
				CLOSE_BG_BOTTOM));
			g2.fill(panel);

			if (hover)
			{
				g2.setColor(CLOSE_HOVER_WASH);
				g2.fill(panel);
			}

			g2.setColor(CLOSE_INSET_HIGHLIGHT);
			g2.setStroke(new BasicStroke(1f));
			g2.draw(new RoundRectangle2D.Float(
				bounds.x + 1f,
				bounds.y + 1f,
				bounds.width - 2f,
				Math.max(1f, bounds.height - 2f),
				Math.max(0f, r - 2f),
				Math.max(0f, r - 2f)));

			g2.setColor(hover ? CLOSE_BORDER_HOVER : CLOSE_BORDER);
			g2.setStroke(new BasicStroke(1f));
			g2.draw(panel);

			int iconPad = Math.max(7, Math.round(bounds.width * 0.30f));
			int x1 = bounds.x + iconPad;
			int y1 = bounds.y + iconPad;
			int x2 = bounds.x + bounds.width - iconPad;
			int y2 = bounds.y + bounds.height - iconPad;
			BasicStroke iconStroke = new BasicStroke(1.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			g2.setStroke(iconStroke);
			g2.setColor(new Color(0, 0, 0, 120));
			g2.drawLine(x1 + 1, y1 + 1, x2 + 1, y2 + 1);
			g2.drawLine(x2 + 1, y1 + 1, x1 + 1, y2 + 1);
			g2.setColor(hover ? CLOSE_ICON_HOVER : CLOSE_ICON);
			g2.drawLine(x1, y1, x2, y2);
			g2.drawLine(x2, y1, x1, y2);
		}
		finally
		{
			g2.dispose();
		}
	}

	/** Scales {@code r} uniformly by {@code scale} about its center, clamping each dimension to at least 1px. */
	static Rectangle scaleRectCentered(Rectangle r, double scale)
	{
		int nw = Math.max(1, (int) Math.round(r.width * scale));
		int nh = Math.max(1, (int) Math.round(r.height * scale));
		int nx = r.x + (r.width - nw) / 2;
		int ny = r.y + (r.height - nh) / 2;
		return new Rectangle(nx, ny, nw, nh);
	}

	/** Scales {@code r}'s width by {@code scaleX} about its horizontal center; height is unchanged. */
	static Rectangle scaleRectHorizontally(Rectangle r, double scaleX)
	{
		int nw = Math.max(1, (int) Math.round(r.width * scaleX));
		int nx = r.x + (r.width - nw) / 2;
		return new Rectangle(nx, r.y, nw, r.height);
	}

	/** Shrinks {@code r} by {@code inset} on all four sides, clamping each dimension to at least 1px. */
	static Rectangle uniformInset(Rectangle r, int inset)
	{
		if (inset <= 0)
		{
			return new Rectangle(r);
		}
		int nw = Math.max(1, r.width - 2 * inset);
		int nh = Math.max(1, r.height - 2 * inset);
		return new Rectangle(r.x + inset, r.y + inset, nw, nh);
	}

	/** Draws a soft glow around {@code r} using the default expand/layer settings and the card's outer arc. */
	static void drawGlow(Graphics2D g, Rectangle r, Color color, float alpha)
	{
		int baseArc = SharedCardRenderer.outerArcDiameter(r.width);
		drawGlow(g, r, color, alpha, GLOW_MAX_EXPAND, GLOW_LAYERS, baseArc);
	}

	/**
	 * Draws a soft glow around {@code r} by blitting a cached, pre-baked layered-rounded-rect image scaled
	 * to {@code alpha}. No-ops for a null/degenerate rect or near-zero alpha.
	 */
	static void drawGlow(Graphics2D g, Rectangle r, Color color, float alpha, float maxExpand, int layers, int baseArc)
	{
		Color glow = color == null ? Color.WHITE : color;
		float clampedAlpha = Math.max(0f, Math.min(1f, alpha));
		if (clampedAlpha <= 0.01f || r == null || r.width < 1 || r.height < 1)
		{
			return;
		}

		int expand = Math.max(1, Math.round(maxExpand));
		BufferedImage baked = cachedGlow(r.width, r.height, glow.getRGB(), expand, layers, baseArc);
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clampedAlpha));
			g2.drawImage(baked, r.x - expand, r.y - expand, null);
		}
		finally
		{
			g2.dispose();
		}
	}

	/**
	 * Returns the baked glow image for this size/color/expand/layer combination, building and caching it
	 * (LRU, capped at {@link #GLOW_CACHE_MAX}) on first use.
	 */
	private static BufferedImage cachedGlow(int width, int height, int rgb, int expand, int layers, int baseArc)
	{
		String key = width + "x" + height + '|' + rgb + '|' + expand + '|' + layers + '|' + baseArc + '|' + GLOW_LAYER_ALPHA;
		BufferedImage cached = GLOW_CACHE.get(key);
		if (cached != null)
		{
			return cached;
		}
		int imgW = Math.max(1, width + expand * 2);
		int imgH = Math.max(1, height + expand * 2);
		BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = img.createGraphics();
		try
		{
			Color glow = new Color(rgb, true);
			int layerCount = Math.max(1, layers);
			for (int i = layerCount; i >= 1; i--)
			{
				float t = (float) i / (float) layerCount;
				int layerExpand = Math.max(1, Math.round((1.0f - t) * expand));
				float falloff = t * t;
				float layerAlpha = falloff * GLOW_LAYER_ALPHA;
				g2.setColor(withAlpha(glow, layerAlpha));
				int arc = baseArc + 2 * layerExpand;
				g2.fillRoundRect(
					expand - layerExpand,
					expand - layerExpand,
					width + (layerExpand * 2),
					height + (layerExpand * 2),
					arc,
					arc
				);
			}
		}
		finally
		{
			g2.dispose();
		}
		GLOW_CACHE.put(key, img);
		return img;
	}

	/** Returns {@code color} (or white if null) with its alpha replaced by {@code alpha}. */
	static Color withAlpha(Color color, float alpha)
	{
		return CardColorMath.withAlpha(color == null ? Color.WHITE : color, alpha);
	}

	/** Returns the largest rect that fits {@code image} inside {@code bounds} preserving aspect ratio, centered. */
	static Rectangle fittedImageRect(Rectangle bounds, BufferedImage image)
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
		int x = bounds.x + (bounds.width - w) / 2;
		int y = bounds.y + (bounds.height - h) / 2;
		return new Rectangle(x, y, w, h);
	}

	/** Draws {@code image} scaled to fit within {@code bounds} preserving aspect ratio, centered. */
	static void drawImageFit(Graphics2D g, BufferedImage image, Rectangle bounds)
	{
		Rectangle r = fittedImageRect(bounds, image);
		g.drawImage(image, r.x, r.y, r.width, r.height, null);
	}

	/** Draws a centered "NEW!" badge with a drop shadow above {@code cardBounds}. */
	static void drawNewBadge(Graphics2D g, Rectangle cardBounds)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setFont(FontManager.getRunescapeBoldFont());
			String text = "NEW!";
			int textX = cardBounds.x + (cardBounds.width / 2) - (g2.getFontMetrics().stringWidth(text) / 2);
			int textY = Math.max(14, cardBounds.y - 8);

			g2.setColor(new Color(0, 0, 0, 180));
			g2.drawString(text, textX + 1, textY + 1);
			g2.setColor(new Color(0xF2C94C));
			g2.drawString(text, textX, textY);
		}
		finally
		{
			g2.dispose();
		}
	}

	/** Draws {@code text} centered near the top of {@code cardBounds} with a drop shadow, faded by {@code alpha}. No-op if {@code text} is null or alpha is near zero. */
	static void drawRarityLabel(Graphics2D g, Rectangle cardBounds, String text, Color color, float alpha)
	{
		float clampedAlpha = Math.max(0f, Math.min(1f, alpha));
		if (text == null || clampedAlpha <= 0.01f)
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setFont(FontManager.getRunescapeBoldFont());
			FontMetrics fm = g2.getFontMetrics();
			int textX = cardBounds.x + (cardBounds.width / 2) - (fm.stringWidth(text) / 2);
			int textY = cardBounds.y + Math.max(fm.getAscent(), Math.round(cardBounds.height / 4f));

			g2.setColor(withAlpha(Color.BLACK, clampedAlpha * (180f / 255f)));
			g2.drawString(text, textX + 1, textY + 1);
			g2.setColor(withAlpha(color == null ? Color.WHITE : color, clampedAlpha));
			g2.drawString(text, textX, textY);
		}
		finally
		{
			g2.dispose();
		}
	}
}

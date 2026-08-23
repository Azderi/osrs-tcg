package com.osrstcg.overlay;

import com.osrstcg.ui.SharedCardRenderer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
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

/** Shared dim/glow/badge/fit drawing for the pack-reveal overlay. */
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
	/** Peak opacity of the innermost baked glow layer (before hover alpha is applied). */
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

	private PackRevealDrawUtil()
	{
	}

	static void drawDim(Graphics2D g, Rectangle canvas)
	{
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		g.setColor(Color.BLACK);
		g.fillRect(canvas.x, canvas.y, canvas.width, canvas.height);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
	}

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

	static void drawScrollWheelHint(Graphics2D g, Rectangle canvas)
	{
		String text = "Scroll to adjust card scale";
		Font font = FontManager.getRunescapeBoldFont();
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics(font);
		int tw = fm.stringWidth(text);
		int x = canvas.x + (canvas.width - tw) / 2;
		int y = canvas.y + Math.max(28, PackRevealLayout.VIEWPORT_EDGE_PAD + 8) + fm.getAscent();
		g.setColor(new Color(0, 0, 0, 220));
		g.drawString(text, x + 2, y + 2);
		g.setColor(new Color(0xFF, 0xF5, 0xDC));
		g.drawString(text, x, y);
	}

	static Rectangle scaleRectCentered(Rectangle r, double scale)
	{
		int nw = Math.max(1, (int) Math.round(r.width * scale));
		int nh = Math.max(1, (int) Math.round(r.height * scale));
		int nx = r.x + (r.width - nw) / 2;
		int ny = r.y + (r.height - nh) / 2;
		return new Rectangle(nx, ny, nw, nh);
	}

	static Rectangle scaleRectHorizontally(Rectangle r, double scaleX)
	{
		int nw = Math.max(1, (int) Math.round(r.width * scaleX));
		int nx = r.x + (r.width - nw) / 2;
		return new Rectangle(nx, r.y, nw, r.height);
	}

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

	static void drawGlow(Graphics2D g, Rectangle r, Color color, float alpha)
	{
		int baseArc = SharedCardRenderer.outerArcDiameter(r.width);
		drawGlow(g, r, color, alpha, GLOW_MAX_EXPAND, GLOW_LAYERS, baseArc);
	}

	/**
	 * @param maxExpand outer halo reach in pixels (smaller = tighter around {@code r})
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

	static Color withAlpha(Color color, float alpha)
	{
		int a = Math.max(0, Math.min(255, (int) Math.round(alpha * 255f)));
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
	}

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

	static void drawImageFit(Graphics2D g, BufferedImage image, Rectangle bounds)
	{
		Rectangle r = fittedImageRect(bounds, image);
		g.drawImage(image, r.x, r.y, r.width, r.height, null);
	}

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

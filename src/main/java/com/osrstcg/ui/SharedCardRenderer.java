package com.runelitetcg.ui;

import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.service.RarityMath;
import com.runelitetcg.util.NumberFormatting;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

public final class SharedCardRenderer
{
	public static final int DEFAULT_CARD_WIDTH = 180;
	public static final int DEFAULT_CARD_HEIGHT = 260;
	/** Border thickness at {@link #DEFAULT_CARD_WIDTH}×{@link #DEFAULT_CARD_HEIGHT}; scaled per draw via {@link #frameThicknessFor}. */
	private static final int FRAME_THICKNESS = 6;
	private static final Color FOIL_FRAME_GOLD = new Color(0xD4AF37);
	/** Light gold fill for the moving foil sheen; opacity comes from {@link AlphaComposite}. */
	private static final Color FOIL_SHEEN_GOLD = new Color(255, 236, 190);
	private static final Color FOIL_SPARKLE_CORE = new Color(255, 215, 105);
	/** Peak opacity when a twinkle is at full size ({@code scaleEnv == 1}). */
	private static final float FOIL_TWINKLE_PEAK_ALPHA = 0.75f;
	/** Inward scoop of foil twinkle star edges; lower = sharper tips (control closer to center). */
	private static final float FOIL_TWINKLE_CONCAVE_K = 0.11f;
	/** Active sheen sweep duration (ms); half of prior 1150 ms = double sweep speed. */
	private static final int FOIL_SHEEN_SWEEP_MS = 575;
	/** Idle time after each sweep before the next (ms). */
	private static final int FOIL_SHEEN_COOLDOWN_MS = 5000;
	private static final int FOIL_SHEEN_CYCLE_MS = FOIL_SHEEN_SWEEP_MS + FOIL_SHEEN_COOLDOWN_MS;
	private static final Color CARD_BG = new Color(0x2A2A2A);
	private static final Color PANEL_DARK = new Color(0x222222);
	private static final Color PANEL_MID = new Color(0x2F2F2F);
	private static final BufferedImage CARD_BACK_IMAGE = ImageUtil.loadImageResource(SharedCardRenderer.class, "/Cardback.png");

	private SharedCardRenderer()
	{
	}

	public static void drawCardBack(Graphics2D g, Rectangle bounds, boolean foil, Color rarityColor)
	{
		if (g == null || bounds == null)
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			enableQuality(g2);
			if (CARD_BACK_IMAGE != null)
			{
				drawCoverCentered(g2, CARD_BACK_IMAGE, bounds);
				return;
			}

			int ft = frameThicknessFor(bounds);
			int outerArc = outerFrameArc(bounds);
			drawFrame(g2, bounds, foil, rarityColor, ft, outerArc);
			Rectangle inner = inset(bounds, ft + 1);
			int innerArc = Math.max(2, outerArc - 2);
			g2.setColor(CARD_BG);
			g2.fillRoundRect(inner.x, inner.y, inner.width, inner.height, innerArc, innerArc);
			drawCenteredText(g2, inner, "OSRS TCG", FontManager.getRunescapeBoldFont(), Color.WHITE);
		}
		finally
		{
			g2.dispose();
		}
	}

	public static void drawCardFace(Graphics2D g, Rectangle bounds, CardDefinition card, boolean foil, Color rarityColor)
	{
		drawCardFace(g, bounds, card, foil, rarityColor, null, 0);
	}

	public static void drawCardFace(Graphics2D g, Rectangle bounds, CardDefinition card, boolean foil, Color rarityColor, BufferedImage linkedImage)
	{
		drawCardFace(g, bounds, card, foil, rarityColor, linkedImage, 0);
	}

	public static void drawCardFace(Graphics2D g, Rectangle bounds, CardDefinition card, boolean foil, Color rarityColor, BufferedImage linkedImage, long basePullDenominator)
	{
		drawCardFace(g, bounds, card, foil, rarityColor, linkedImage, basePullDenominator, foil);
	}

	/**
	 * @param useFoilAdjustedScoreForLabel when true, stats line uses {@link RarityMath#foilAdjustedScoreRounded};
	 *        when false, uses rounded base {@link RarityMath#score}. Frame foil is still {@code foil}.
	 */
	public static void drawCardFace(Graphics2D g, Rectangle bounds, CardDefinition card, boolean foil, Color rarityColor, BufferedImage linkedImage, long basePullDenominator,
		boolean useFoilAdjustedScoreForLabel)
	{
		if (g == null || bounds == null)
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			enableQuality(g2);
			int ft = frameThicknessFor(bounds);
			int outerArc = outerFrameArc(bounds);
			drawFrame(g2, bounds, foil, rarityColor, ft, outerArc);

			Rectangle inner = inset(bounds, ft + 1);
			int innerArc = Math.max(2, outerArc - 2);
			g2.setColor(CARD_BG);
			g2.fillRoundRect(inner.x, inner.y, inner.width, inner.height, innerArc, innerArc);

			int y = inner.y;
			int titleH = pct(inner.height, 0.10d);
			int imageH = pct(inner.height, 0.40d);
			int tierH = pct(inner.height, 0.10d);
			int examineH = pct(inner.height, 0.30d);
			int statsH = inner.height - titleH - imageH - tierH - examineH;

			Rectangle titleR = new Rectangle(inner.x, y, inner.width, titleH);
			y += titleH;
			Rectangle imageR = new Rectangle(inner.x, y, inner.width, imageH);
			y += imageH;
			Rectangle tierR = new Rectangle(inner.x, y, inner.width, tierH);
			y += tierH;
			Rectangle examineR = new Rectangle(inner.x, y, inner.width, examineH);
			y += examineH;
			Rectangle statsR = new Rectangle(inner.x, y, inner.width, statsH);

			Color themedDark = blend(PANEL_DARK, safeColor(rarityColor), 0.32f);
			Color themedMid = blend(PANEL_MID, safeColor(rarityColor), 0.20f);
			fillSection(g2, titleR, themedDark);
			fillSection(g2, imageR, themedMid);
			fillSection(g2, tierR, themedDark);
			fillSection(g2, examineR, themedMid);
			fillSection(g2, statsR, themedDark);

			drawCenteredText(g2, titleR, valueOrFallback(card == null ? null : card.getName(), "Unknown Card"),
				FontManager.getRunescapeSmallFont(), safeColor(rarityColor).brighter(), 2);

			drawImageSection(g2, imageR, card, linkedImage);

			String rarity = tierLabelForRarityColor(safeColor(rarityColor));
			drawCenteredText(g2, tierR, rarity, FontManager.getRunescapeSmallFont(), safeColor(rarityColor).brighter());

			Font exFont = FontManager.getRunescapeSmallFont();
			FontMetrics fmExamine = g2.getFontMetrics(exFont);
			int examineLineH = Math.max(1, fmExamine.getHeight());
			// Never allow more lines than fit in the examine band (was Math.max(6, …), which overflowed small cards).
			int examineVerticalPad = 6;
			int maxExamineLines = Math.max(1, (examineR.height - examineVerticalPad) / examineLineH);

			String examine = valueOrFallback(card == null ? null : card.getExamine(), "No examine text.");
			drawWrappedCentered(g2, examineR, examine, exFont, ColorScheme.LIGHT_GRAY_COLOR, maxExamineLines, 8);

			String stats = "Score: " + formatScore(card, useFoilAdjustedScoreForLabel);
			drawCenteredText(g2, statsR, stats, FontManager.getRunescapeSmallFont(), Color.WHITE);

			if (foil)
			{
				drawFoilSheen(g2, bounds, outerArc);
				drawFoilSparkles(g2, bounds, outerArc, ft, card);
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	/** Deterministic fraction in {@code [0, 1)} for foil sparkle layout (per star index + salt). */
	private static double sparkleU01(long seed, int i, int salt)
	{
		long z = seed + (long) i * 0x9E3779B97L + (long) salt * 0xC2B2AE3D27A4EB4FL;
		z ^= z >>> 33;
		z *= 0xff51afd7ed558ccdL;
		z ^= z >>> 33;
		z *= 0xc4ceb9fe1a85ec53L;
		z ^= z >>> 33;
		long m = (z >>> 12) & ((1L << 52) - 1);
		return m * 0x1.0p-52;
	}

	/**
	 * Four-point star with concave edges between points (taller than wide), centered at the origin.
	 * @param halfW east–west extent from center to tip
	 * @param halfH north–south extent from center to tip (typically &gt; halfW)
	 */
	private static Path2D.Float foilTwinklePath(float halfW, float halfH)
	{
		float k = FOIL_TWINKLE_CONCAVE_K;
		Path2D.Float p = new Path2D.Float();
		p.moveTo(0f, -halfH);
		p.quadTo(k * halfW, -k * halfH, halfW, 0f);
		p.quadTo(k * halfW, k * halfH, 0f, halfH);
		p.quadTo(-k * halfW, k * halfH, -halfW, 0f);
		p.quadTo(-k * halfW, -k * halfH, 0f, -halfH);
		p.closePath();
		return p;
	}

	/**
	 * Diagonal semi-transparent gold band (45°) over the full card (including frame). One fast sweep every
	 * {@link #FOIL_SHEEN_SWEEP_MS}, then {@link #FOIL_SHEEN_COOLDOWN_MS} idle.
	 */
	private static void drawFoilSheen(Graphics2D g2, Rectangle clipBounds, int clipArc)
	{
		if (g2 == null || clipBounds == null || clipBounds.width < 6 || clipBounds.height < 6)
		{
			return;
		}

		long t = System.currentTimeMillis();
		long cyclePos = t % FOIL_SHEEN_CYCLE_MS;
		if (cyclePos >= FOIL_SHEEN_SWEEP_MS)
		{
			return;
		}
		double u = cyclePos / (double) FOIL_SHEEN_SWEEP_MS;

		int arc = Math.max(2, clipArc);
		java.awt.Shape clipBefore = g2.getClip();
		java.awt.Composite compositeBefore = g2.getComposite();
		java.awt.geom.AffineTransform transformBefore = g2.getTransform();
		try
		{
			RoundRectangle2D clipShape = new RoundRectangle2D.Float(
				clipBounds.x, clipBounds.y, clipBounds.width, clipBounds.height, arc, arc);
			g2.clip(clipShape);

			double cx = clipBounds.getCenterX();
			double cy = clipBounds.getCenterY();
			double diag = Math.hypot(clipBounds.width, clipBounds.height);
			int sheenW = Math.max(10, (int) Math.round(Math.min(clipBounds.width, clipBounds.height) * 0.15d));
			int span = (int) Math.ceil(diag) + sheenW + 8;
			double th = Math.PI / 4.0d;
			// Screen-space half-height of the rotated band (axis-aligned bounds).
			double halfBandH = 0.5d * (Math.abs(sheenW * Math.sin(th)) + Math.abs(span * Math.cos(th)));
			double extra = Math.max(4.0d, sheenW);
			// u=0: band fully above the card; u=1: fully below — then loop.
			double travelStart = -clipBounds.height / 2.0d - halfBandH - extra;
			double travelEnd = clipBounds.height / 2.0d + halfBandH + extra;
			double travel = travelStart + u * (travelEnd - travelStart);

			g2.translate(cx, cy + travel);
			g2.rotate(th);

			g2.setComposite(AlphaComposite.SrcOver.derive(0.40f));
			g2.setColor(FOIL_SHEEN_GOLD);
			g2.fillRect(-sheenW / 2, -span / 2, sheenW, span);
		}
		finally
		{
			g2.setTransform(transformBefore);
			g2.setComposite(compositeBefore);
			g2.setClip(clipBefore);
		}
	}

	/** Fewer sparkles; each twinkle grows from 0 to full size then shrinks to 0; position changes each cycle. */
	private static void drawFoilSparkles(Graphics2D g2, Rectangle clipBounds, int clipArc, int frameThickness, CardDefinition card)
	{
		if (g2 == null || clipBounds == null || clipBounds.width < 8 || clipBounds.height < 8)
		{
			return;
		}

		int arc = Math.max(2, clipArc);
		String key = valueOrFallback(card == null ? null : card.getName(), "Unknown");
		long seed = (long) key.hashCode() ^ ((long) key.length() << 32) ^ 0xC0FFEE8BADF00DL;

		java.awt.Shape clipBefore = g2.getClip();
		java.awt.Composite compositeBefore = g2.getComposite();
		java.awt.Stroke strokeBefore = g2.getStroke();
		try
		{
			float scale = Math.max(0.35f, Math.min(clipBounds.width, clipBounds.height) / 260.0f);
			float twinkleArmPx = Math.max(5f, (float) (3.55d * scale * 1.62d * 2.0d));

			// Inside the frame, with extra pad so full-size star tips stay off the border.
			int borderInset = Math.max(1, frameThickness) + 1;
			int tipPad = Math.max(2, (int) Math.ceil(twinkleArmPx * 1.12f));
			Rectangle sparkleArea = inset(clipBounds, borderInset);
			int sparkleArc = Math.max(2, arc - 2);
			Rectangle posArea = inset(sparkleArea, tipPad);
			if (posArea.width < 6 || posArea.height < 6)
			{
				posArea = sparkleArea;
			}

			RoundRectangle2D clipShape = new RoundRectangle2D.Float(
				sparkleArea.x, sparkleArea.y, sparkleArea.width, sparkleArea.height, sparkleArc, sparkleArc);
			g2.clip(clipShape);

			int count = 6 + (int) (sparkleU01(seed, 0, 77) * 6);
			int margin = Math.max(1, Math.round(2 * scale));
			int posW = Math.max(1, posArea.width - margin * 2);
			int posH = Math.max(1, posArea.height - margin * 2);
			long now = System.currentTimeMillis();

			for (int i = 0; i < count; i++)
			{
				int period = 1100 + (int) (sparkleU01(seed, i, 6) * 2400);
				int phase = (int) (sparkleU01(seed, i, 7) * period);
				long tWave = now + phase;
				long cycleIndex = tWave / period;
				long posSeed = seed ^ (cycleIndex * 0x9E3779B97L) ^ ((long) i << 21);

				int px = posArea.x + margin + (int) (sparkleU01(posSeed, i, 1) * posW);
				int py = posArea.y + margin + (int) (sparkleU01(posSeed, i, 2) * posH);

				long uMod = ((tWave % period) + period) % period;
				double u = uMod / (double) period;
				// One smooth pulse per period: 0 → 1 → 0 (size and fade).
				double scaleEnv = Math.sin(Math.PI * u);
				if (scaleEnv < 0.012d)
				{
					continue;
				}

				float halfW = twinkleArmPx * 0.86f;
				float halfH = twinkleArmPx * 1.12f;
				Path2D.Float twShape = foilTwinklePath(halfW, halfH);
				AffineTransform at = new AffineTransform();
				at.translate(px, py);
				at.scale(scaleEnv, scaleEnv);
				java.awt.Shape drawn = at.createTransformedShape(twShape);

				g2.setColor(FOIL_SPARKLE_CORE);
				g2.setComposite(AlphaComposite.SrcOver.derive(FOIL_TWINKLE_PEAK_ALPHA * (float) scaleEnv));
				g2.fill(drawn);
			}
		}
		finally
		{
			g2.setStroke(strokeBefore);
			g2.setComposite(compositeBefore);
			g2.setClip(clipBefore);
		}
	}

	private static int frameThicknessFor(Rectangle bounds)
	{
		if (bounds == null || bounds.width < 4 || bounds.height < 4)
		{
			return 2;
		}
		double scale = Math.min(
			bounds.width / (double) DEFAULT_CARD_WIDTH,
			bounds.height / (double) DEFAULT_CARD_HEIGHT);
		int t = (int) Math.round(FRAME_THICKNESS * scale);
		int maxBySize = Math.max(2, Math.min(bounds.width, bounds.height) / 4);
		return Math.max(2, Math.min(t, maxBySize));
	}

	private static int outerFrameArc(Rectangle bounds)
	{
		if (bounds == null)
		{
			return 12;
		}
		int a = (int) Math.round(12.0d * bounds.width / (double) DEFAULT_CARD_WIDTH);
		int cap = Math.max(4, Math.min(bounds.width, bounds.height) / 2);
		return Math.max(3, Math.min(a, cap));
	}

	@SuppressWarnings("unused")
	private static void drawFrame(Graphics2D g2, Rectangle bounds, boolean foil, Color accent, int frameThickness, int outerArc)
	{
		g2.setColor(foil ? FOIL_FRAME_GOLD : Color.BLACK);
		g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, outerArc, outerArc);
		Color strokeColor = foil ? new Color(120, 90, 20, 200) : new Color(0, 0, 0, 180);
		g2.setColor(strokeColor);
		for (int i = 0; i < frameThickness; i++)
		{
			g2.drawRoundRect(bounds.x + i, bounds.y + i, bounds.width - (i * 2) - 1, bounds.height - (i * 2) - 1, outerArc, outerArc);
		}

		if (foil)
		{
			int innerArc = Math.max(2, outerArc - 2);
			g2.setColor(new Color(255, 255, 255, 28));
			g2.drawRoundRect(bounds.x + frameThickness, bounds.y + frameThickness,
				bounds.width - (frameThickness * 2) - 1, bounds.height - (frameThickness * 2) - 1, innerArc, innerArc);
			return;
		}

		g2.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, outerArc, outerArc);
	}

	private static void fillSection(Graphics2D g2, Rectangle rect, Color base)
	{
		g2.setPaint(new GradientPaint(rect.x, rect.y, base.brighter(), rect.x, rect.y + rect.height, base));
		g2.fillRoundRect(rect.x + 1, rect.y + 1, Math.max(1, rect.width - 2), Math.max(1, rect.height - 2), 6, 6);
	}

	private static void drawCenteredText(Graphics2D g2, Rectangle rect, String text, Font font, Color color)
	{
		drawCenteredText(g2, rect, text, font, color, 0);
	}

	private static void drawCenteredText(Graphics2D g2, Rectangle rect, String text, Font font, Color color, int horizontalPadding)
	{
		g2.setFont(font == null ? FontManager.getRunescapeSmallFont() : font);
		g2.setColor(color == null ? Color.WHITE : color);
		FontMetrics fm = g2.getFontMetrics();
		int safePadding = Math.max(0, horizontalPadding);
		int maxWidth = Math.max(1, rect.width - (safePadding * 2));
		String value = ellipsizeToWidth(valueOrFallback(text, ""), fm, maxWidth);
		int x = rect.x + safePadding + Math.max(0, (maxWidth - fm.stringWidth(value)) / 2);
		int y = rect.y + ((rect.height - fm.getHeight()) / 2) + fm.getAscent();
		g2.drawString(value, x, y);
	}

	private static void drawWrappedCentered(Graphics2D g2, Rectangle rect, String text, Font font, Color color, int maxLines, int horizontalPadding)
	{
		g2.setFont(font == null ? FontManager.getRunescapeSmallFont() : font);
		g2.setColor(color == null ? Color.WHITE : color);
		FontMetrics fm = g2.getFontMetrics();
		String value = valueOrFallback(text, "");

		int safePadding = Math.max(0, horizontalPadding);
		int maxWidth = Math.max(20, rect.width - (safePadding * 2));
		int linesCap = Math.max(1, maxLines);
		String[] words = value.split("\\s+");
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String rawWord : words)
		{
			String word = rawWord == null ? "" : rawWord.trim();
			if (word.isEmpty())
			{
				continue;
			}

			String candidate = current.length() == 0 ? word : current + " " + word;
			if (fm.stringWidth(candidate) <= maxWidth)
			{
				current = new StringBuilder(candidate);
				continue;
			}

			if (current.length() > 0)
			{
				lines.add(current.toString());
			}
			current = new StringBuilder(word);
		}

		if (current.length() > 0)
		{
			lines.add(current.toString());
		}

		if (lines.isEmpty())
		{
			lines.add("");
		}

		if (lines.size() > linesCap)
		{
			java.util.List<String> kept = new java.util.ArrayList<>(lines.subList(0, linesCap - 1));
			StringBuilder tail = new StringBuilder();
			for (int i = linesCap - 1; i < lines.size(); i++)
			{
				if (tail.length() > 0)
				{
					tail.append(' ');
				}
				tail.append(lines.get(i));
			}
			kept.add(ellipsizeToWidth(tail.toString(), fm, maxWidth));
			lines = kept;
		}

		int lastIdx = lines.size() - 1;
		lines.set(lastIdx, ellipsizeToWidth(lines.get(lastIdx), fm, maxWidth));

		int lineHeight = fm.getHeight();
		int totalHeight = lineHeight * lines.size();
		int y = rect.y + (rect.height - totalHeight) / 2 + fm.getAscent();

		java.awt.Shape previousClip = g2.getClip();
		try
		{
			g2.clip(rect);
			for (String lineText : lines)
			{
				int x = rect.x + safePadding + Math.max(0, (maxWidth - fm.stringWidth(lineText)) / 2);
				g2.drawString(lineText, x, y);
				y += lineHeight;
			}
		}
		finally
		{
			g2.setClip(previousClip);
		}
	}

	private static void drawTwoLineCentered(Graphics2D g2, Rectangle rect, String top, String bottom, Font font, Color color)
	{
		g2.setFont(font == null ? FontManager.getRunescapeSmallFont() : font);
		g2.setColor(color == null ? Color.WHITE : color);
		FontMetrics fm = g2.getFontMetrics();
		int totalHeight = fm.getHeight() * 2;
		int startY = rect.y + (rect.height - totalHeight) / 2 + fm.getAscent();

		String t = valueOrFallback(top, "");
		String b = valueOrFallback(bottom, "");
		int topX = rect.x + (rect.width - fm.stringWidth(t)) / 2;
		int bottomX = rect.x + (rect.width - fm.stringWidth(b)) / 2;
		g2.drawString(t, topX, startY);
		g2.drawString(b, bottomX, startY + fm.getHeight());
	}

	private static void drawFitCentered(Graphics2D g2, BufferedImage image, Rectangle rect)
	{
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		if (sourceWidth <= 0 || sourceHeight <= 0)
		{
			return;
		}
		double ratio = Math.min((double) rect.width / sourceWidth, (double) rect.height / sourceHeight);
		int w = Math.max(1, (int) Math.round(sourceWidth * ratio));
		int h = Math.max(1, (int) Math.round(sourceHeight * ratio));
		int x = rect.x + (rect.width - w) / 2;
		int y = rect.y + (rect.height - h) / 2;
		java.awt.Shape previousClip = g2.getClip();
		try
		{
			g2.setClip(rect);
			g2.drawImage(image, x, y, w, h, null);
		}
		finally
		{
			g2.setClip(previousClip);
		}
	}

	private static void drawCoverCentered(Graphics2D g2, BufferedImage image, Rectangle rect)
	{
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		if (sourceWidth <= 0 || sourceHeight <= 0)
		{
			return;
		}

		double ratio = Math.max((double) rect.width / sourceWidth, (double) rect.height / sourceHeight);
		int w = Math.max(1, (int) Math.round(sourceWidth * ratio));
		int h = Math.max(1, (int) Math.round(sourceHeight * ratio));
		int x = rect.x + (rect.width - w) / 2;
		int y = rect.y + (rect.height - h) / 2;

		java.awt.Shape previousClip = g2.getClip();
		try
		{
			g2.setClip(rect);
			g2.drawImage(image, x, y, w, h, null);
		}
		finally
		{
			g2.setClip(previousClip);
		}
	}

	private static void drawImageSection(Graphics2D g2, Rectangle imageRect, CardDefinition card, BufferedImage linkedImage)
	{
		if (linkedImage != null)
		{
			drawFitCentered(g2, linkedImage, inset(imageRect, 4));
			return;
		}

		String imageUrl = card == null ? null : card.getImageUrl();
		String artText = (imageUrl != null && !imageUrl.trim().isEmpty()) ? "Loading artwork..." : "No artwork";
		drawCenteredText(g2, imageRect, artText, FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR);
	}

	private static Rectangle inset(Rectangle r, int pad)
	{
		return new Rectangle(r.x + pad, r.y + pad, Math.max(1, r.width - (pad * 2)), Math.max(1, r.height - (pad * 2)));
	}

	private static int pct(int total, double fraction)
	{
		return Math.max(1, (int) Math.round(total * fraction));
	}

	private static void enableQuality(Graphics2D g2)
	{
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}

	/** Tier label (Common … Godly) from the accent color used on card frames, matching sidebar / pack reveal. */
	public static String tierLabelForRarityColor(Color color)
	{
		return rarityName(color);
	}

	/** Same score basis as pack rolls and sidebar lists: {@link RarityMath#score}. */
	public static double cardDisplayScore(CardDefinition card)
	{
		return RarityMath.score(card);
	}

	private static String rarityName(Color color)
	{
		if (color == null)
		{
			return "Common";
		}

		if (matches(color, 0xF2C94C))
		{
			return "Godly";
		}
		if (matches(color, 0xFF6EC7))
		{
			return "Mythic";
		}
		if (matches(color, 0xE74C3C))
		{
			return "Legendary";
		}
		if (matches(color, 0x9B59B6))
		{
			return "Epic";
		}
		if (matches(color, 0x3498DB))
		{
			return "Rare";
		}
		if (matches(color, 0x2ECC71))
		{
			return "Uncommon";
		}
		return "Common";
	}

	private static boolean matches(Color color, int rgb)
	{
		return color.getRGB() == new Color(rgb).getRGB();
	}

	private static String valueOrFallback(String value, String fallback)
	{
		return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
	}

	private static String formatScore(CardDefinition card, boolean foilAdjusted)
	{
		if (card == null)
		{
			return "-";
		}
		long score = foilAdjusted ? RarityMath.foilAdjustedScoreRounded(card) : Math.round(RarityMath.score(card));
		return NumberFormatting.format(score);
	}

	private static Color blend(Color base, Color accent, float accentWeight)
	{
		float w = Math.max(0f, Math.min(1f, accentWeight));
		int r = Math.round(base.getRed() * (1f - w) + accent.getRed() * w);
		int g = Math.round(base.getGreen() * (1f - w) + accent.getGreen() * w);
		int b = Math.round(base.getBlue() * (1f - w) + accent.getBlue() * w);
		return new Color(clamp255(r), clamp255(g), clamp255(b));
	}

	private static int clamp255(int value)
	{
		return Math.max(0, Math.min(255, value));
	}

	private static Color safeColor(Color color)
	{
		return color == null ? Color.WHITE : color;
	}

	private static String ellipsizeToWidth(String text, FontMetrics fm, int maxWidth)
	{
		if (text == null)
		{
			return "";
		}
		if (fm.stringWidth(text) <= maxWidth)
		{
			return text;
		}

		String ellipsis = "...";
		int ellipsisWidth = fm.stringWidth(ellipsis);
		if (ellipsisWidth >= maxWidth)
		{
			return "";
		}

		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++)
		{
			char ch = text.charAt(i);
			if (fm.stringWidth(out.toString() + ch) + ellipsisWidth > maxWidth)
			{
				break;
			}
			out.append(ch);
		}
		return out + ellipsis;
	}
}

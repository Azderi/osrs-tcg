package com.osrstcg.ui;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.ui.card.CardColorMath;
import com.osrstcg.ui.card.CardFaceDrawRequest;
import com.osrstcg.ui.card.CardFonts;
import com.osrstcg.ui.card.CardFxPainter;
import com.osrstcg.ui.card.ExamineTextLayout;
import com.osrstcg.ui.card.FoilFx;
import com.osrstcg.ui.card.WearFx;
import com.osrstcg.util.NumberFormatting;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import net.runelite.client.ui.ColorScheme;

public final class SharedCardRenderer
{
	public static final int DEFAULT_CARD_WIDTH = 180;
	public static final int DEFAULT_CARD_HEIGHT = 260;

	private static final Color FRAME_DARK = new Color(0x1A1A1A);
	private static final Color FOIL_FRAME_GOLD = new Color(0xD4AF37);
	private static final Color PANEL_DARK = new Color(0x222222);
	private static final Color PANEL_MID = new Color(0x2F2F2F);
	private static final Color FULL_ART_WELL = new Color(0x1A1A1A);
	private static final Color LOCK_BADGE_BG = new Color(20, 24, 32, 209);
	private static final Color LOCK_BADGE_BORDER = new Color(158, 200, 255, 115);
	private static final Color LOCK_BADGE_ICON = new Color(0x9EC8FF);
	private static final Color BETA_BADGE_BG = new Color(26, 58, 110, 235);
	private static final Color BETA_BADGE_BORDER = new Color(0x3D7EFF);
	private static final Color BETA_BADGE_TEXT = new Color(0xB8D4FF);

	private static final int[] BAND_FRACTIONS = {10, 40, 10, 30, 10};
	private static final float BANDED_TITLE_EM_MIN = 0.80f;
	private static final int BANDED_TITLE_FIT_STEPS = 10;

	public static final String CARD_BACK_PATH = "/images/Cardback_new.png";

	private SharedCardRenderer()
	{
	}

	// ------------------------------------------------------------------ public API

	public static boolean drawCardFaceIfCached(Graphics2D g, Rectangle bounds, CardFaceDrawRequest req)
	{
		if (g == null || bounds == null || req == null || bounds.width < 4 || bounds.height < 4)
		{
			return false;
		}
		BufferedImage face = CardFaceCache.getIfPresent(bounds.width, bounds.height, req);
		if (face == null)
		{
			return false;
		}
		paintCachedFace(g, bounds, req, face);
		return true;
	}

	private static void paintCachedFace(Graphics2D g, Rectangle bounds, CardFaceDrawRequest req, BufferedImage face)
	{
		g.drawImage(face, bounds.x, bounds.y, null);

		FoilFx foilFx = req.getFoilFx();
		if (foilFx != null && req.isFoil() && req.isDrawFoilOverlays())
		{
			double scale = bounds.width / (double) DEFAULT_CARD_WIDTH;
			CardFxPainter.drawAnimatedSparkles(
				g,
				bounds.x,
				bounds.y,
				bounds.width,
				bounds.height,
				outerRadius(bounds.width),
				scale,
				foilFx,
				System.nanoTime() / 1_000_000_000.0d);
		}
	}

	public static void drawCardBack(Graphics2D g, Rectangle bounds, boolean foil, Color rarityColor)
	{
		drawCardBack(g, bounds, foil, rarityColor, null);
	}

	public static void drawCardBack(Graphics2D g, Rectangle bounds, boolean foil, Color rarityColor,
		BufferedImage cardBack)
	{
		if (g == null || bounds == null || bounds.width < 2 || bounds.height < 2)
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			enableQuality(g2);
			double radius = outerRadius(bounds.width);
			Shape card = new RoundRectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height, radius * 2.0d, radius * 2.0d);
			g2.clip(card);

			if (cardBack != null)
			{
				drawCoverCentered(g2, cardBack, bounds);
				return;
			}

			g2.setColor(foil ? FOIL_FRAME_GOLD : FRAME_DARK);
			g2.fill(card);
			CardTextLayout.drawCenteredText(g2, bounds, "OSRS TCG", CardFonts.bold(bounds.width / (double) DEFAULT_CARD_WIDTH), Color.WHITE, 0);
		}
		finally
		{
			g2.dispose();
		}
	}

	public static String tierLabelForRarityColor(Color color)
	{
		if (color == null)
		{
			return RarityMath.Tier.COMMON.getLabel();
		}
		for (RarityMath.Tier tier : RarityMath.Tier.values())
		{
			if (tier.getColor().getRGB() == color.getRGB())
			{
				return tier.getLabel();
			}
		}
		return RarityMath.Tier.COMMON.getLabel();
	}

	public static void prewarmFace(int w, int h, CardFaceDrawRequest req)
	{
		if (req == null || w < 4 || h < 4 || CardFaceCache.expectsArtButMissing(req))
		{
			return;
		}
		CardFaceCache.cachedFace(w, h, req);
	}

	public static boolean isFaceCached(int w, int h, CardFaceDrawRequest req)
	{
		if (req == null || w < 4 || h < 4)
		{
			return false;
		}
		return CardFaceCache.contains(w, h, req);
	}

	// ------------------------------------------------------------------ raster build

	static BufferedImage renderFace(int w, int h, CardFaceDrawRequest req)
	{
		BufferedImage face = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Geometry geo = new Geometry(w, h);

		Graphics2D g2 = face.createGraphics();
		try
		{
			enableQuality(g2);
			paintBase(g2, geo, req);
		}
		finally
		{
			g2.dispose();
		}

		WearFx wear = req.getWear();
		if (wear != null)
		{
			CardFxPainter.applyWearFilter(face, wear.getFade());
			CardFxPainter.drawWear(face, geo.outerRadius, wear);
		}
		return face;
	}

	private static void paintBase(Graphics2D g2, Geometry geo, CardFaceDrawRequest req)
	{
		if (req.isFullArt())
		{
			paintFullArt(g2, geo, req);
			return;
		}
		paintBanded(g2, geo, req);
	}

	private static void paintBanded(Graphics2D g2, Geometry geo, CardFaceDrawRequest req)
	{
		CardDefinition card = req.getCard();
		Color rarity = req.getRarityColor();

		Shape outer = new RoundRectangle2D.Double(0, 0, geo.width, geo.height, geo.outerRadius * 2.0d, geo.outerRadius * 2.0d);
		g2.setColor(req.isFoil() ? FOIL_FRAME_GOLD : FRAME_DARK);
		g2.fill(outer);

		if (req.isFoil())
		{
			drawInsetRing(g2, geo, new Color(255, 255, 255, 31));
		}

		Color themedDark = CardColorMath.blendColors(PANEL_DARK, rarity, 0.32d);
		Color themedMid = CardColorMath.blendColors(PANEL_MID, rarity, 0.20d);
		fillBand(g2, geo.title, themedDark, geo.bandRadius);
		fillBand(g2, geo.art, themedMid, geo.bandRadius);
		fillBand(g2, geo.tier, themedDark, geo.bandRadius);
		fillBand(g2, geo.examine, themedMid, geo.bandRadius);
		fillBand(g2, geo.score, themedDark, geo.bandRadius);

		Color titleColor = CardColorMath.brighterColor(rarity);
		String titleText = CardTextLayout.valueOrFallback(card == null ? null : card.getName(), "Unknown Card");
		int titleMaxWidth = Math.max(8, geo.title.width - geo.bandPadX * 2);
		Font titleFont = fitBandedTitleFont(g2, titleText, titleMaxWidth, geo.scale);
		int titleNudgeY = Math.max(2, (int) Math.round(3.0d * geo.scale));
		Rectangle titleBox = new Rectangle(
			geo.title.x,
			geo.title.y + titleNudgeY,
			geo.title.width,
			Math.max(1, geo.title.height - titleNudgeY));
		CardTextLayout.drawWrappedCentered(g2, titleBox, titleText, titleFont, titleColor, 1, geo.bandPadX, true);

		drawArt(g2, geo, req);
		drawBandedBadges(g2, geo, req);

		String tierLabel = CardTextLayout.valueOrFallback(req.getTierLabel(), tierLabelForRarityColor(rarity));
		CardTextLayout.drawCenteredText(g2, geo.tier, tierLabel, CardFonts.bold(geo.scale), titleColor, geo.bandPadX);

		drawExamine(g2, geo, CardTextLayout.valueOrFallback(card == null ? null : card.getExamine(), "No examine text."));

		CardTextLayout.drawCenteredText(g2, geo.score, "Score: " + scoreText(req),
			CardFonts.bold(geo.scale), Color.WHITE, geo.bandPadX);
	}

	private static void paintFullArt(Graphics2D g2, Geometry geo, CardFaceDrawRequest req)
	{
		CardDefinition card = req.getCard();
		Color rarity = req.getRarityColor();
		String tierLabel = CardTextLayout.valueOrFallback(req.getTierLabel(), tierLabelForRarityColor(rarity));
		boolean godlyRim = "Godly".equalsIgnoreCase(tierLabel);

		Object prevTextAa = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		Object prevFrac = g2.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

		Shape outer = new RoundRectangle2D.Double(0, 0, geo.width, geo.height, geo.outerRadius * 2.0d, geo.outerRadius * 2.0d);
		g2.setColor(godlyRim ? FOIL_FRAME_GOLD : rarity);
		g2.fill(outer);

		drawInsetRing(g2, geo, new Color(255, 255, 255, 46));

		Shape well = new RoundRectangle2D.Double(
			geo.innerX, geo.innerY, geo.innerW, geo.innerH,
			geo.innerRadius * 2.0d, geo.innerRadius * 2.0d);
		g2.setColor(FULL_ART_WELL);
		g2.fill(well);

		Shape prevClip = g2.getClip();
		try
		{
			g2.clip(well);
			BufferedImage art = req.getArt();
			if (art != null)
			{
				drawCoverCentered(g2, art, new Rectangle(geo.innerX, geo.innerY, geo.innerW, geo.innerH));
			}
			else
			{
				String imagePath = card == null ? null : card.getFoilImagePath();
				String artText = (imagePath != null && !imagePath.trim().isEmpty()) ? "Loading artwork..." : "No artwork";
				CardTextLayout.drawCenteredText(g2, new Rectangle(geo.innerX, geo.innerY, geo.innerW, geo.innerH),
					artText, CardFonts.body(geo.scale), ColorScheme.LIGHT_GRAY_COLOR, geo.fullPadX);
			}

			int titlePadY = Math.max(1, (int) Math.round(2.0d * geo.scale));
			int titlePadX = Math.max(1, (int) Math.round(6.0d * geo.scale));
			int titleNudgeY = Math.max(2, (int) Math.round(3.0d * geo.scale));
			Font titleFont = CardFonts.fullArtTitle(geo.scale);
			FontMetrics titleFm = g2.getFontMetrics(titleFont);
			int titleScrimH = Math.min(geo.innerH,
				titlePadY + titleNudgeY + titleFm.getHeight() + Math.max(2, (int) Math.round(4.0d * geo.scale)));
			Rectangle titleScrim = new Rectangle(geo.innerX, geo.innerY, geo.innerW, titleScrimH);
			paintVerticalScrim(g2, titleScrim, true);

			g2.setFont(CardFonts.fullArtTitle(1.0d));
			String titleText = CardTextLayout.ellipsizeFullArtTitle(g2.getFontMetrics(),
				card == null ? null : card.getName());
			Rectangle titleBox = new Rectangle(
				geo.innerX + titlePadX, geo.innerY + titlePadY + titleNudgeY,
				Math.max(8, geo.innerW - titlePadX * 2),
				Math.max(8, titleScrimH - titlePadY - titleNudgeY));
			drawCenteredTextShadowed(g2, titleBox, titleText, titleFont, CardColorMath.brighterColor(rarity), 0, geo.scale, false, true);

			String examineRaw = card == null || card.getExamine() == null ? "" : card.getExamine().trim();
			g2.setFont(CardFonts.fullArtExamine(1.0d));
			List<String> examLines = CardTextLayout.wrapFullArtExamine(g2.getFontMetrics(), examineRaw);
			if (!examLines.isEmpty())
			{
				int examineW = Math.max(8, geo.innerW - Math.max(1, (int) Math.round(12.0d * geo.scale)));
				Font examineFont = CardFonts.fullArtExamine(geo.scale);
				int lineH = Math.max(1, (int) Math.round(examineFont.getSize2D() * 1.2d));
				int blockH = lineH * examLines.size();
				int centerY = geo.innerY + (int) Math.round(geo.innerH * 0.75d);
				int top = centerY - blockH / 2;
				int left = geo.innerX + (geo.innerW - examineW) / 2;
				drawFullArtExamine(g2, left, top, examineW, examLines, examineFont, lineH, geo.scale);
			}

			Font scoreFont = CardFonts.fullArtScore(geo.scale);
			FontMetrics scoreFm = g2.getFontMetrics(scoreFont);
			int scorePadY = titlePadY;
			int scorePadX = titlePadX;
			int scoreScrimH = Math.min(geo.innerH,
				scorePadY * 2 + scoreFm.getHeight() + Math.max(2, (int) Math.round(4.0d * geo.scale)));
			Rectangle scoreScrim = new Rectangle(geo.innerX, geo.innerY + geo.innerH - scoreScrimH, geo.innerW, scoreScrimH);
			paintVerticalScrim(g2, scoreScrim, false);
			Rectangle scoreBox = new Rectangle(
				geo.innerX + scorePadX, scoreScrim.y + scorePadY,
				Math.max(8, geo.innerW - scorePadX * 2),
				Math.max(8, scoreScrimH - scorePadY * 2));
			drawCenteredTextShadowed(g2, scoreBox, "Score: " + scoreText(req), scoreFont, Color.WHITE, 0, geo.scale);

			drawFullArtBadges(g2, geo, req);
		}
		finally
		{
			g2.setClip(prevClip);
			if (prevTextAa != null)
			{
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, prevTextAa);
			}
			if (prevFrac != null)
			{
				g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, prevFrac);
			}
		}
	}

	private static void drawInsetRing(Graphics2D g2, Geometry geo, Color color)
	{
		float ringWidth = (float) Math.max(1.0d, Math.round(geo.scale));
		Shape ring = new RoundRectangle2D.Double(
			ringWidth / 2.0d, ringWidth / 2.0d,
			geo.width - ringWidth, geo.height - ringWidth,
			Math.max(0.0d, geo.outerRadius * 2.0d - ringWidth), Math.max(0.0d, geo.outerRadius * 2.0d - ringWidth));
		g2.setColor(color);
		g2.setStroke(new BasicStroke(ringWidth));
		g2.draw(ring);
		g2.setStroke(new BasicStroke(1f));
	}

	private static void paintVerticalScrim(Graphics2D g2, Rectangle rect, boolean fromTop)
	{
		if (rect.height <= 0 || rect.width <= 0)
		{
			return;
		}
		float x1 = rect.x;
		float x2 = rect.x;
		float y1 = fromTop ? rect.y : rect.y + rect.height;
		float y2 = fromTop ? rect.y + rect.height : rect.y;
		java.awt.LinearGradientPaint paint = new java.awt.LinearGradientPaint(
			x1, y1, x2, y2,
			new float[]{0f, 0.70f, 1f},
			new Color[]{
				new Color(0, 0, 0, 140),
				new Color(0, 0, 0, 71),
				new Color(0, 0, 0, 0)});
		g2.setPaint(paint);
		g2.fillRect(rect.x, rect.y, rect.width, rect.height);
	}

	private static void drawFullArtExamine(Graphics2D g2, int left, int top, int width,
		List<String> lines, Font font, int lineHeight, double scale)
	{
		g2.setFont(font);
		FontMetrics fm = g2.getFontMetrics();
		float stroke = (float) Math.max(0.75d, 1.0d * scale);
		int y = top + fm.getAscent() + Math.max(0, (lineHeight - fm.getHeight()) / 2);
		for (String line : lines)
		{
			int x = left + Math.max(0, (width - fm.stringWidth(line)) / 2);
			drawStrokedShadowedString(g2, line, x, y, Color.WHITE, stroke, scale);
			y += lineHeight;
		}
	}

	private static void drawStrokedShadowedString(Graphics2D g2, String text, int x, int y,
		Color fill, float strokeWidth, double scale)
	{
		int drop = Math.max(1, (int) Math.round(1.0d * scale));
		java.awt.font.GlyphVector gv = g2.getFont().createGlyphVector(g2.getFontRenderContext(), text);
		Shape outline = gv.getOutline(x, y);
		Shape dropOutline = gv.getOutline(x, y + drop);

		g2.setColor(new Color(0, 0, 0, 179));
		g2.fill(dropOutline);

		g2.setColor(new Color(0, 0, 0, 235));
		g2.setStroke(new BasicStroke(Math.max(1f, strokeWidth * 2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.draw(outline);
		g2.setColor(fill == null ? Color.WHITE : fill);
		g2.fill(outline);
		g2.setStroke(new BasicStroke(1f));
	}

	private static void drawCenteredTextShadowed(Graphics2D g2, Rectangle rect, String text, Font font,
		Color color, int horizontalPadding, double scale)
	{
		drawCenteredTextShadowed(g2, rect, text, font, color, horizontalPadding, scale, true);
	}

	private static void drawCenteredTextShadowed(Graphics2D g2, Rectangle rect, String text, Font font,
		Color color, int horizontalPadding, double scale, boolean ellipsize)
	{
		drawCenteredTextShadowed(g2, rect, text, font, color, horizontalPadding, scale, ellipsize, false);
	}

	private static void drawCenteredTextShadowed(Graphics2D g2, Rectangle rect, String text, Font font,
		Color color, int horizontalPadding, double scale, boolean ellipsize, boolean topAlign)
	{
		g2.setFont(font == null ? CardFonts.fullArtScore(1.0d) : font);
		FontMetrics fm = g2.getFontMetrics();
		int pad = Math.max(0, horizontalPadding);
		int maxWidth = Math.max(1, rect.width - pad * 2);
		String value = ellipsize
			? CardTextLayout.ellipsizeToWidth(CardTextLayout.valueOrFallback(text, ""), fm, maxWidth)
			: CardTextLayout.valueOrFallback(text, "");
		int x = rect.x + pad + Math.max(0, (maxWidth - fm.stringWidth(value)) / 2);
		int y = topAlign
			? rect.y + fm.getAscent()
			: rect.y + ((rect.height - fm.getHeight()) / 2) + fm.getAscent();
		Shape clip = g2.getClip();
		try
		{
			g2.clip(rect);
			drawTitleScoreShadow(g2, value, x, y, color, scale);
		}
		finally
		{
			g2.setClip(clip);
		}
	}

	private static void drawTitleScoreShadow(Graphics2D g2, String text, int x, int y, Color color, double scale)
	{
		int drop = Math.max(1, (int) Math.round(1.0d * scale));
		g2.setColor(new Color(0, 0, 0, 230));
		g2.drawString(text, x, y + drop);
		g2.drawString(text, x + drop, y + drop);
		g2.setColor(new Color(0, 0, 0, 242));
		g2.drawString(text, x - 1, y);
		g2.drawString(text, x + 1, y);
		g2.drawString(text, x, y - 1);
		g2.drawString(text, x, y + 1);
		g2.setColor(color == null ? Color.WHITE : color);
		g2.drawString(text, x, y);
	}

	private static void drawBandedBadges(Graphics2D g2, Geometry geo, CardFaceDrawRequest req)
	{
		if (!req.isLocked() && !req.isBeta())
		{
			return;
		}
		drawBadgesAt(g2, geo.art.x, geo.art.y, geo.art.width, geo.scale, req.isLocked(), req.isBeta());
	}

	private static void drawFullArtBadges(Graphics2D g2, Geometry geo, CardFaceDrawRequest req)
	{
		if (!req.isLocked() && !req.isBeta())
		{
			return;
		}
		drawBadgesAt(g2, geo.innerX, geo.innerY, geo.innerW, geo.scale, req.isLocked(), req.isBeta());
	}

	private static void drawBadgesAt(Graphics2D g2, int originX, int originY, int parentWidth, double scale,
		boolean locked, boolean beta)
	{
		int inset = Math.max(1, (int) Math.round(4.0d * scale));
		if (locked)
		{
			int size = Math.max(8, (int) Math.round(20.0d * scale));
			int x = originX + inset;
			int y = originY + inset;
			int radius = Math.max(2, (int) Math.round(5.0d * scale));
			float border = (float) Math.max(1.0d, 1.5d * scale);
			g2.setColor(LOCK_BADGE_BG);
			g2.fillRoundRect(x, y, size, size, radius * 2, radius * 2);
			g2.setColor(LOCK_BADGE_BORDER);
			g2.setStroke(new BasicStroke(border));
			g2.drawRoundRect(x, y, size, size, radius * 2, radius * 2);
			g2.setStroke(new BasicStroke(1f));
			drawLockIcon(g2, x, y, size, LOCK_BADGE_ICON);
		}
		if (beta)
		{
			Font font = CardFonts.bold(scale).deriveFont(Math.max(6f, (float) (11.0d * scale)));
			g2.setFont(font);
			FontMetrics fm = g2.getFontMetrics();
			String label = "BETA";
			int padX = Math.max(2, (int) Math.round(6.0d * scale));
			int h = Math.max(8, (int) Math.round(18.0d * scale));
			int w = fm.stringWidth(label) + padX * 2;
			int x = originX + parentWidth - inset - w;
			int y = originY + inset;
			int radius = Math.max(2, (int) Math.round(6.0d * scale));
			float border = (float) Math.max(1.0d, 1.5d * scale);
			int nudgeX = Math.max(0, (int) Math.round(0.5d * scale));
			int nudgeY = Math.max(0, (int) Math.round(1.0d * scale));
			g2.setColor(BETA_BADGE_BG);
			g2.fillRoundRect(x + nudgeX, y + nudgeY, w, h, radius * 2, radius * 2);
			g2.setColor(BETA_BADGE_BORDER);
			g2.setStroke(new BasicStroke(border));
			g2.drawRoundRect(x + nudgeX, y + nudgeY, w, h, radius * 2, radius * 2);
			g2.setStroke(new BasicStroke(1f));
			g2.setColor(BETA_BADGE_TEXT);
			int tx = x + nudgeX + (w - fm.stringWidth(label)) / 2;
			int ty = y + nudgeY + (h - fm.getHeight()) / 2 + fm.getAscent();
			g2.drawString(label, tx, ty);
		}
	}

	private static void drawLockIcon(Graphics2D g2, int badgeX, int badgeY, int badgeSize, Color color)
	{
		int icon = Math.max(6, (int) Math.round(badgeSize * 12.0d / 20.0d));
		int x = badgeX + (badgeSize - icon) / 2;
		int y = badgeY + (badgeSize - icon) / 2;
		g2.setColor(color);
		int bodyH = Math.max(2, (int) Math.round(icon * 0.45d));
		int bodyY = y + icon - bodyH;
		int shackleW = Math.max(2, (int) Math.round(icon * 0.55d));
		int shackleX = x + (icon - shackleW) / 2;
		int shackleH = Math.max(2, (int) Math.round(icon * 0.4d));
		g2.fillRoundRect(x, bodyY, icon, bodyH, 2, 2);
		g2.setStroke(new BasicStroke(Math.max(1f, icon / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.drawArc(shackleX, y, shackleW, shackleH * 2, 0, 180);
		g2.setStroke(new BasicStroke(1f));
	}

	private static void fillBand(Graphics2D g2, Rectangle band, Color fill, int radius)
	{
		g2.setColor(fill);
		g2.fillRoundRect(band.x, band.y, band.width, band.height, radius * 2, radius * 2);
	}

	private static void drawArt(Graphics2D g2, Geometry geo, CardFaceDrawRequest req)
	{
		Rectangle inner = inset(geo.art, geo.artPad);
		BufferedImage art = req.getArt();
		if (art != null)
		{
			drawFitCentered(g2, art, inner);
			return;
		}

		CardDefinition card = req.getCard();
		String imageUrl = card == null ? null : card.getImageUrl();
		String artText = (imageUrl != null && !imageUrl.trim().isEmpty()) ? "Loading artwork..." : "No artwork";
		CardTextLayout.drawCenteredText(g2, geo.art, artText, CardFonts.body(geo.scale),
			ColorScheme.LIGHT_GRAY_COLOR, geo.bandPadX);
	}

	private static void drawExamine(Graphics2D g2, Geometry geo, String examine)
	{
		String text = CardTextLayout.valueOrFallback(examine, "No examine text.");
		int maxWidth = Math.max(1, geo.examine.width - geo.bandPadX * 2);
		int bandHeight = geo.examine.height;

		float em = ExamineTextLayout.fitExamineEm(g2::getFontMetrics, geo.scale, text, maxWidth, bandHeight);
		Font font = CardFonts.examine(geo.scale, em);
		FontMetrics fm = g2.getFontMetrics(font);
		List<String> lines = ExamineTextLayout.wrapBreakWord(fm, text, maxWidth);
		int lineHeight = ExamineTextLayout.lineHeightPx(font.getSize2D());

		g2.setFont(font);
		g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		int blockHeight = lines.size() * lineHeight;
		int y = geo.examine.y + (bandHeight - blockHeight) / 2 + fm.getAscent();
		int pad = geo.bandPadX;

		Shape clip = g2.getClip();
		try
		{
			g2.clip(geo.examine);
			for (String line : lines)
			{
				int x = geo.examine.x + pad + Math.max(0, (maxWidth - fm.stringWidth(line)) / 2);
				g2.drawString(line, x, y);
				y += lineHeight;
			}
		}
		finally
		{
			g2.setClip(clip);
		}
	}

	static String scoreText(CardFaceDrawRequest req)
	{
		Long server = req.getDisplayScore();
		if (server != null)
		{
			return NumberFormatting.format(server.longValue());
		}
		CardDefinition card = req.getCard();
		if (card == null)
		{
			return "-";
		}
		boolean foil = req.isFullArt() || req.isUseFoilAdjustedScore();
		return NumberFormatting.format(card.displayScore(foil));
	}

	// ------------------------------------------------------------------ geometry

	private static final class Geometry
	{
		private final int width;
		private final int height;
		private final double scale;
		private final double outerRadius;
		private final int innerX;
		private final int innerY;
		private final int innerW;
		private final int innerH;
		private final int innerRadius;
		private final int bandRadius;
		private final int bandPadX;
		private final int fullPadX;
		private final int artPad;
		private final Rectangle title;
		private final Rectangle art;
		private final Rectangle tier;
		private final Rectangle examine;
		private final Rectangle score;

		private Geometry(int width, int height)
		{
			this.width = width;
			this.height = height;
			this.scale = width / (double) DEFAULT_CARD_WIDTH;
			this.outerRadius = outerRadius(width);
			this.bandRadius = Math.max(1, (int) Math.round(6.0d * scale));
			this.bandPadX = Math.max(1, (int) Math.round(4.0d * scale));
			this.fullPadX = Math.max(1, (int) Math.round(6.0d * scale));
			this.artPad = Math.max(1, (int) Math.round(2.0d * scale));
			this.innerRadius = Math.max(1, (int) Math.round(4.0d * scale));

			int rim = Math.max(1, Math.min(Math.min(width, height) / 4, (int) Math.round(7.0d * scale)));
			this.innerX = rim;
			this.innerY = rim;
			this.innerW = Math.max(1, width - rim * 2);
			this.innerH = Math.max(1, height - rim * 2);

			int gap = Math.max(2, (int) Math.round(2.0d * scale));
			int gaps = BAND_FRACTIONS.length - 1;
			if (gap * gaps >= innerH)
			{
				gap = Math.max(0, (innerH - BAND_FRACTIONS.length) / Math.max(1, gaps));
			}
			int available = Math.max(BAND_FRACTIONS.length, innerH - gap * gaps);

			Rectangle[] bands = new Rectangle[BAND_FRACTIONS.length];
			int cumulative = 0;
			int prevEdge = 0;
			for (int i = 0; i < BAND_FRACTIONS.length; i++)
			{
				cumulative += BAND_FRACTIONS[i];
				int edge = (int) Math.round(available * cumulative / 100.0d);
				int top = innerY + prevEdge + i * gap;
				int bandHeight = Math.max(1, edge - prevEdge);
				bands[i] = new Rectangle(innerX, top, innerW, bandHeight);
				prevEdge = edge;
			}

			this.title = bands[0];
			this.art = bands[1];
			this.tier = bands[2];
			this.examine = bands[3];
			this.score = bands[4];
		}
	}

	private static double outerRadius(int width)
	{
		return Math.max(1.0d, 11.0d * width / (double) DEFAULT_CARD_WIDTH);
	}

	public static int outerArcDiameter(int width)
	{
		return Math.max(2, (int) Math.round(outerRadius(width) * 2.0d));
	}

	private static Rectangle inset(Rectangle r, int pad)
	{
		return new Rectangle(r.x + pad, r.y + pad, Math.max(1, r.width - pad * 2), Math.max(1, r.height - pad * 2));
	}

	// ------------------------------------------------------------------ text / image helpers

	private static Font fitBandedTitleFont(Graphics2D g2, String title, int maxWidth, double scale)
	{
		String text = CardTextLayout.valueOrFallback(title, "");
		int width = Math.max(1, maxWidth);
		Font largest = CardFonts.title(scale, CardFonts.TITLE_EM);
		if (g2.getFontMetrics(largest).stringWidth(text) <= width)
		{
			return largest;
		}
		float bestEm = BANDED_TITLE_EM_MIN;
		float lo = BANDED_TITLE_EM_MIN;
		float hi = CardFonts.TITLE_EM;
		for (int i = 0; i < BANDED_TITLE_FIT_STEPS; i++)
		{
			float mid = (lo + hi) * 0.5f;
			Font candidate = CardFonts.title(scale, mid);
			if (g2.getFontMetrics(candidate).stringWidth(text) <= width)
			{
				bestEm = mid;
				lo = mid;
			}
			else
			{
				hi = mid;
			}
		}
		return CardFonts.title(scale, bestEm);
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
		Shape clip = g2.getClip();
		try
		{
			g2.clip(rect);
			g2.drawImage(image, x, y, w, h, null);
		}
		finally
		{
			g2.setClip(clip);
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
		g2.drawImage(image, x, y, w, h, null);
	}

	private static void enableQuality(Graphics2D g2)
	{
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
	}
}

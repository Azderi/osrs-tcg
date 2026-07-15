package com.osrstcg.ui;

import com.osrstcg.data.CardDefinition;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import net.runelite.client.util.ImageUtil;

public final class CardEffectRenderer
{
	private static final double MAX_TILT_DEGREES = 20.0d;
	private static final double HOLOGRAPHIC_IDLE_STRENGTH = 0.18d;
	private static final float HOLOGRAPHIC_ALPHA = 0.30f;
	private static final int STRIP_HEIGHT = 2;
	private static final BufferedImage[] HOLO_SPARKLE_ICONS = loadHoloSparkleIcons();

	private CardEffectRenderer()
	{
	}

	public static void drawCardFace(Graphics2D g, Rectangle bounds, CardDefinition card, boolean foil,
		Color rarityColor, BufferedImage linkedImage, long basePullDenominator, boolean useFoilAdjustedScoreForLabel,
		double pointerX, double pointerY, double effectStrength, boolean holographic)
	{
		drawCardFace(g, bounds, card, foil, rarityColor, linkedImage, basePullDenominator,
			useFoilAdjustedScoreForLabel, pointerX, pointerY, effectStrength, holographic, Options.allEnabled());
	}

	public static void drawCardFace(Graphics2D g, Rectangle bounds, CardDefinition card, boolean foil,
		Color rarityColor, BufferedImage linkedImage, long basePullDenominator, boolean useFoilAdjustedScoreForLabel,
		double pointerX, double pointerY, double effectStrength, boolean holographic, Options options)
	{
		if (g == null || bounds == null)
		{
			return;
		}
		BufferedImage image = renderFace(bounds, card, foil, rarityColor, linkedImage, basePullDenominator,
			useFoilAdjustedScoreForLabel, !(holographic && linkedImage != null));
		drawEffectCard(g, bounds, image, foil, rarityColor, linkedImage, pointerX, pointerY, effectStrength, holographic, options);
	}

	public static void drawCardBack(Graphics2D g, Rectangle bounds, boolean foil, Color rarityColor,
		double pointerX, double pointerY, double effectStrength, boolean holographic)
	{
		drawCardBack(g, bounds, foil, rarityColor, pointerX, pointerY, effectStrength, holographic, Options.allEnabled());
	}

	public static void drawCardBack(Graphics2D g, Rectangle bounds, boolean foil, Color rarityColor,
		double pointerX, double pointerY, double effectStrength, boolean holographic, Options options)
	{
		if (g == null || bounds == null)
		{
			return;
		}
		BufferedImage image = renderBack(bounds, foil, rarityColor);
		drawEffectCard(g, bounds, image, foil, rarityColor, null, pointerX, pointerY, effectStrength, holographic, options);
	}

	private static BufferedImage renderFace(Rectangle bounds, CardDefinition card, boolean foil, Color rarityColor,
		BufferedImage linkedImage, long basePullDenominator, boolean useFoilAdjustedScoreForLabel, boolean renderLinkedArtwork)
	{
		BufferedImage image = new BufferedImage(Math.max(1, bounds.width), Math.max(1, bounds.height), BufferedImage.TYPE_INT_ARGB);
		Graphics2D ig = image.createGraphics();
		try
		{
			enableQuality(ig);
			SharedCardRenderer.drawCardFace(ig, new Rectangle(0, 0, image.getWidth(), image.getHeight()), card,
				foil, rarityColor, linkedImage, basePullDenominator, useFoilAdjustedScoreForLabel, false, renderLinkedArtwork);
			applyCardAlphaMask(ig, image.getWidth(), image.getHeight());
		}
		finally
		{
			ig.dispose();
		}
		return image;
	}

	private static BufferedImage renderBack(Rectangle bounds, boolean foil, Color rarityColor)
	{
		BufferedImage image = new BufferedImage(Math.max(1, bounds.width), Math.max(1, bounds.height), BufferedImage.TYPE_INT_ARGB);
		Graphics2D ig = image.createGraphics();
		try
		{
			enableQuality(ig);
			SharedCardRenderer.drawCardBack(ig, new Rectangle(0, 0, image.getWidth(), image.getHeight()), foil, rarityColor);
			applyCardAlphaMask(ig, image.getWidth(), image.getHeight());
		}
		finally
		{
			ig.dispose();
		}
		return image;
	}

	private static void drawEffectCard(Graphics2D g, Rectangle bounds, BufferedImage image, boolean foil, Color rarityColor,
		BufferedImage linkedImage,
		double pointerX, double pointerY, double effectStrength, boolean holographic, Options options)
	{
		if (image == null || bounds.width <= 0 || bounds.height <= 0)
		{
			return;
		}
		Options opts = options == null ? Options.allEnabled() : options;

		double strength = clamp01(effectStrength);
		double visualStrength = Math.max(strength, holographic && opts.holographic ? HOLOGRAPHIC_IDLE_STRENGTH : 0.0d);
		double nx = clamp((pointerX - bounds.getCenterX()) / Math.max(1.0d, bounds.width / 2.0d), -1.0d, 1.0d);
		double ny = clamp((pointerY - bounds.getCenterY()) / Math.max(1.0d, bounds.height / 2.0d), -1.0d, 1.0d);
		double tiltX = -ny * MAX_TILT_DEGREES * strength;
		double tiltY = nx * MAX_TILT_DEGREES * strength;
		double effectNx = nx * strength;
		double effectNy = ny * strength;

		Projection p = project(bounds, tiltX, tiltY);
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			enableQuality(g2);
			if (opts.shadow)
			{
				drawShadow(g2, p, visualStrength);
			}
			if (opts.depth)
			{
				drawDepthEdge(g2, p, tiltX, tiltY, rarityColor, visualStrength);
			}
			BufferedImage decorated = decorateCardImage(image, foil, rarityColor, linkedImage, effectNx, effectNy, visualStrength, holographic && opts.holographic);
			drawWarpedImage(g2, decorated, p);
			if (opts.glare)
			{
				drawGlare(g2, p, nx, ny, strength);
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	private static Projection project(Rectangle bounds, double tiltXDegrees, double tiltYDegrees)
	{
		double rx = Math.toRadians(tiltXDegrees);
		double ry = Math.toRadians(tiltYDegrees);
		double cosX = Math.cos(rx);
		double sinX = Math.sin(rx);
		double cosY = Math.cos(ry);
		double sinY = Math.sin(ry);
		double cx = bounds.getCenterX();
		double cy = bounds.getCenterY();
		double halfW = bounds.width / 2.0d;
		double halfH = bounds.height / 2.0d;
		double camera = Math.max(bounds.width, bounds.height) * 3.1d;

		double[][] src = {
			{-halfW, -halfH, 0.0d},
			{halfW, -halfH, 0.0d},
			{halfW, halfH, 0.0d},
			{-halfW, halfH, 0.0d}
		};
		int[] x = new int[4];
		int[] y = new int[4];
		for (int i = 0; i < src.length; i++)
		{
			double px = src[i][0];
			double py = src[i][1];
			double pz = src[i][2];

			double y1 = py * cosX - pz * sinX;
			double z1 = py * sinX + pz * cosX;
			double x2 = px * cosY + z1 * sinY;
			double z2 = -px * sinY + z1 * cosY;
			double scale = camera / (camera + z2);
			x[i] = (int) Math.round(cx + x2 * scale);
			y[i] = (int) Math.round(cy + y1 * scale);
		}
		return new Projection(x, y);
	}

	private static BufferedImage decorateCardImage(BufferedImage image, boolean foil, Color rarityColor, BufferedImage linkedImage,
		double nx, double ny, double strength, boolean holographic)
	{
		if (!holographic)
		{
			return image;
		}
		BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		try
		{
			enableQuality(g);
			g.drawImage(image, 0, 0, null);
			drawParallaxArtwork(g, image.getWidth(), image.getHeight(), linkedImage, nx, ny, strength);
			float alpha = (float) (HOLOGRAPHIC_ALPHA * (foil ? 1.0d : 0.55d) * (0.50d + strength * 0.50d));
			Shape clip = new RoundRectangle2D.Float(0, 0, image.getWidth(), image.getHeight(),
				Math.max(6, image.getWidth() / 13), Math.max(6, image.getWidth() / 13));
			Shape oldClip = g.getClip();
			Composite oldComposite = g.getComposite();
			java.awt.Paint oldPaint = g.getPaint();
			AffineTransform oldTransform = g.getTransform();
			java.awt.Stroke oldStroke = g.getStroke();
			try
			{
				g.setClip(clip);
				g.setComposite(AlphaComposite.SrcAtop.derive(alpha));
				float angleShift = (float) (nx * 0.46d - ny * 0.32d);
				float parallaxX = (float) (nx * image.getWidth() * 0.045d);
				float parallaxY = (float) (ny * image.getHeight() * 0.035d);
				float x0 = (float) (-image.getWidth() * 0.88f + angleShift * image.getWidth() + parallaxX);
				float x1 = x0 + image.getWidth() * 2.6f;
				float y0 = (float) (-image.getHeight() * 0.15f + parallaxY);
				float y1 = image.getHeight() * 1.15f + parallaxY;
				g.setPaint(new LinearGradientPaint(
					x0, y0, x1, y1,
					new float[] {0.00f, 0.14f, 0.28f, 0.43f, 0.58f, 0.74f, 0.88f, 1.00f},
					new Color[] {
						new Color(255, 40, 110),
						new Color(255, 160, 55),
						new Color(255, 245, 90),
						new Color(80, 240, 150),
						new Color(70, 225, 255),
						new Color(95, 120, 255),
						new Color(215, 85, 255),
						new Color(255, 40, 110)
					}));
				g.fillRect(0, 0, image.getWidth(), image.getHeight());

				g.setComposite(AlphaComposite.SrcAtop.derive(alpha * 0.42f));
				g.setStroke(new BasicStroke(Math.max(1f, image.getWidth() / 180f)));
				g.rotate(Math.toRadians(-18.0d), image.getWidth() / 2.0d, image.getHeight() / 2.0d);
				int lineStep = Math.max(5, image.getWidth() / 18);
				int lineOffset = (int) Math.round((nx * 1.5d + ny * 0.85d) * lineStep + parallaxY);
				for (int y = -image.getHeight() * 2 + lineOffset; y < image.getHeight() * 3; y += lineStep)
				{
					float u = (float) ((Math.sin((y + nx * image.getWidth() * 0.42d + ny * image.getHeight() * 0.25d) * 0.045d) + 1.0d) * 0.5d);
					g.setColor(new Color(
						(int) (120 + 135 * u),
						(int) (210 + 45 * (1.0f - u)),
						255,
						120));
					g.drawLine(-image.getWidth(), y, image.getWidth() * 2, y);
				}

				g.setTransform(oldTransform);
				drawHolographicSparkles(g, image.getWidth(), image.getHeight(), nx, ny, strength, alpha);

				g.setComposite(AlphaComposite.SrcAtop.derive(alpha * (0.65f + (float) strength * 0.35f)));
				double shimmerX = image.getWidth() * (0.5d + nx * 0.34d);
				double shimmerY = image.getHeight() * (0.5d + ny * 0.22d);
				double radius = Math.max(image.getWidth(), image.getHeight()) * 0.52d;
				g.setPaint(new java.awt.RadialGradientPaint(
					(float) shimmerX,
					(float) shimmerY,
					(float) radius,
					new float[] {0.0f, 0.38f, 1.0f},
					new Color[] {
						new Color(255, 255, 255, 210),
						withAlpha(rarityColor == null ? Color.WHITE : rarityColor.brighter(), 0.30f),
						new Color(255, 255, 255, 0)
					}));
				g.fillRect(0, 0, image.getWidth(), image.getHeight());

				restoreArtworkFocus(g, image.getWidth(), image.getHeight(), linkedImage, nx, ny, strength);
			}
			finally
			{
				g.setStroke(oldStroke);
				g.setTransform(oldTransform);
				g.setPaint(oldPaint);
				g.setComposite(oldComposite);
				g.setClip(oldClip);
			}
		}
		finally
		{
			g.dispose();
		}
		return out;
	}

	private static void drawParallaxArtwork(Graphics2D g, int width, int height, BufferedImage linkedImage,
		double nx, double ny, double strength)
	{
		if (linkedImage == null || width <= 0 || height <= 0 || linkedImage.getWidth() <= 0 || linkedImage.getHeight() <= 0)
		{
			return;
		}

		Rectangle artRect = cardArtworkRect(width, height);
		if (artRect.width <= 2 || artRect.height <= 2)
		{
			return;
		}

		Shape oldClip = g.getClip();
		Composite oldComposite = g.getComposite();
		try
		{
			g.clip(artRect);
			double baseRatio = Math.min((double) artRect.width / linkedImage.getWidth(), (double) artRect.height / linkedImage.getHeight());
			double depthScale = 1.055d + strength * 0.035d;
			int drawW = Math.max(1, (int) Math.round(linkedImage.getWidth() * baseRatio * depthScale));
			int drawH = Math.max(1, (int) Math.round(linkedImage.getHeight() * baseRatio * depthScale));
			int maxShiftX = Math.max(2, artRect.width / 16);
			int maxShiftY = Math.max(2, artRect.height / 14);
			int shiftX = (int) Math.round(-nx * maxShiftX * (0.45d + strength * 0.55d));
			int shiftY = (int) Math.round(-ny * maxShiftY * (0.45d + strength * 0.55d));
			int x = artRect.x + (artRect.width - drawW) / 2 + shiftX;
			int y = artRect.y + (artRect.height - drawH) / 2 + shiftY;

			g.setComposite(AlphaComposite.SrcOver.derive((float) (0.72d + strength * 0.22d)));
			g.drawImage(linkedImage, x, y, drawW, drawH, null);

			g.setComposite(AlphaComposite.SrcOver.derive((float) (0.12d + strength * 0.10d)));
			int shineX = artRect.x + (int) Math.round((0.5d + nx * 0.35d) * artRect.width);
			g.setPaint(new GradientPaint(
				shineX - artRect.width / 2, artRect.y, new Color(255, 255, 255, 0),
				shineX, artRect.y + artRect.height / 2, new Color(255, 255, 255, 120),
				true));
			g.fillRect(artRect.x, artRect.y, artRect.width, artRect.height);
		}
		finally
		{
			g.setComposite(oldComposite);
			g.setClip(oldClip);
		}
	}

	private static Rectangle cardArtworkRect(int width, int height)
	{
		double scale = Math.min(width / (double) SharedCardRenderer.DEFAULT_CARD_WIDTH,
			height / (double) SharedCardRenderer.DEFAULT_CARD_HEIGHT);
		int frameThickness = Math.max(2, (int) Math.round(6.0d * scale));
		int inset = frameThickness + 1;
		Rectangle inner = new Rectangle(inset, inset, Math.max(1, width - inset * 2), Math.max(1, height - inset * 2));
		int titleH = Math.max(1, (int) Math.round(inner.height * 0.10d));
		int imageH = Math.max(1, (int) Math.round(inner.height * 0.40d));
		int pad = Math.max(2, (int) Math.round(4.0d * scale));
		return new Rectangle(
			inner.x + pad,
			inner.y + titleH + pad,
			Math.max(1, inner.width - pad * 2),
			Math.max(1, imageH - pad * 2));
	}

	private static void restoreArtworkFocus(Graphics2D g, int width, int height, BufferedImage linkedImage,
		double nx, double ny, double strength)
	{
		if (linkedImage == null || width <= 0 || height <= 0 || linkedImage.getWidth() <= 0 || linkedImage.getHeight() <= 0)
		{
			return;
		}
		Rectangle artRect = cardArtworkRect(width, height);
		if (artRect.width <= 2 || artRect.height <= 2)
		{
			return;
		}

		Shape oldClip = g.getClip();
		Composite oldComposite = g.getComposite();
		java.awt.Paint oldPaint = g.getPaint();
		try
		{
			g.clip(artRect);
			double baseRatio = Math.min((double) artRect.width / linkedImage.getWidth(), (double) artRect.height / linkedImage.getHeight());
			double depthScale = 1.055d + strength * 0.035d;
			int drawW = Math.max(1, (int) Math.round(linkedImage.getWidth() * baseRatio * depthScale));
			int drawH = Math.max(1, (int) Math.round(linkedImage.getHeight() * baseRatio * depthScale));
			int maxShiftX = Math.max(2, artRect.width / 16);
			int maxShiftY = Math.max(2, artRect.height / 14);
			int shiftX = (int) Math.round(-nx * maxShiftX * (0.45d + strength * 0.55d));
			int shiftY = (int) Math.round(-ny * maxShiftY * (0.45d + strength * 0.55d));
			int x = artRect.x + (artRect.width - drawW) / 2 + shiftX;
			int y = artRect.y + (artRect.height - drawH) / 2 + shiftY;

			g.setComposite(AlphaComposite.SrcOver.derive((float) (0.34d + strength * 0.14d)));
			g.drawImage(linkedImage, x, y, drawW, drawH, null);

			g.setComposite(AlphaComposite.SrcOver.derive(0.16f));
			g.setPaint(new GradientPaint(
				artRect.x, artRect.y, new Color(255, 255, 255, 105),
				artRect.x, artRect.y + artRect.height, new Color(0, 0, 0, 92)));
			g.fillRect(artRect.x, artRect.y, artRect.width, artRect.height);
		}
		finally
		{
			g.setPaint(oldPaint);
			g.setComposite(oldComposite);
			g.setClip(oldClip);
		}
	}

	private static void drawHolographicSparkles(Graphics2D g, int width, int height, double nx, double ny,
		double strength, float baseAlpha)
	{
		double vx = -nx * 0.75d;
		double vy = -ny * 0.75d;
		double vz = 1.0d;
		double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
		vx /= vLen;
		vy /= vLen;
		vz /= vLen;
		double viewAngle = Math.atan2(vy, vx);
		double viewLean = Math.min(1.0d, Math.hypot(nx, ny));

		int[] cellSizes = {
			Math.max(10, width / 12),
			Math.max(14, width / 8),
			Math.max(20, width / 5),
			Math.max(30, width / 3)
		};
		for (int layer = 0; layer < cellSizes.length; layer++)
		{
			int cell = cellSizes[layer];
			double depth = 0.45d + layer * 0.32d;
			double px = nx * width * 0.055d * depth;
			double py = ny * height * 0.045d * depth;
			int minCx = (int) Math.floor((-cell - px) / cell);
			int maxCx = (int) Math.ceil((width + cell - px) / cell);
			int minCy = (int) Math.floor((-cell - py) / cell);
			int maxCy = (int) Math.ceil((height + cell - py) / cell);
			for (int cy = minCy; cy <= maxCy; cy++)
			{
				for (int cx = minCx; cx <= maxCx; cx++)
				{
					long seed = sparkleSeed(cx, cy, layer);
					double sx = (cx + sparkleU(seed, 1)) * cell + px;
					double sy = (cy + sparkleU(seed, 2)) * cell + py;
					if (sx < -cell || sx > width + cell || sy < -cell || sy > height + cell)
					{
						continue;
					}
					double bandCoord = (sx + sy * 0.68d + nx * width * 0.20d + ny * height * 0.12d) / Math.max(1.0d, cell * 1.65d);
					double bandWave = Math.cos(bandCoord * Math.PI * 2.0d);
					double bandMask = 0.25d + 0.75d * Math.max(0.0d, (bandWave - 0.38d) / 0.62d);

					double dx = sparkleU(seed, 3) * 2.0d - 1.0d;
					double dy = sparkleU(seed, 4) * 2.0d - 1.0d;
					double dz = 0.52d + sparkleU(seed, 5) * 0.72d;
					double nLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
					dx /= nLen;
					dy /= nLen;
					dz /= nLen;
					double dot = dx * vx + dy * vy + dz * vz;
					double gate = Math.max(0.0d, (dot - (0.40d - strength * 0.10d)) / 0.60d);
					double preferredAngle = sparkleU(seed, 9) * Math.PI * 2.0d - Math.PI;
					double angleGate = Math.max(0.0d, (Math.cos(viewAngle - preferredAngle) - 0.08d) / 0.92d);
					angleGate = Math.pow(angleGate, 1.15d) * (0.48d + viewLean * 0.52d);
					gate *= angleGate;
					gate *= bandMask * bandMask;
					if (gate <= 0.0d)
					{
						continue;
					}

					float alpha = (float) Math.min(0.88d, baseAlpha * 2.35d * gate * (0.98d - layer * 0.08d));
					if (alpha <= 0.008f)
					{
						continue;
					}
					float radius = (float) Math.max(3.5d, cell * (0.11d + sparkleU(seed, 7) * 0.09d));
					Color core = sparkleTint(seed, alpha);
					BufferedImage icon = sparkleIcon(seed);
					if (icon != null)
					{
						double rotation = viewAngle * 0.35d + sparkleU(seed, 10) * Math.PI * 2.0d;
						drawIconSparkle(g, icon, sx, sy, radius * 2.5f, core, alpha, rotation);
					}
					else
					{
						drawFallbackSparkle(g, sx, sy, radius, core, alpha);
					}
				}
			}
		}
	}

	private static BufferedImage[] loadHoloSparkleIcons()
	{
		BufferedImage partyHat = ImageUtil.loadImageResource(CardEffectRenderer.class, "/holo_icons/party_hat.png");
		BufferedImage saradominStar = ImageUtil.loadImageResource(CardEffectRenderer.class, "/holo_icons/saradomin_star.png");
		BufferedImage windBolt = ImageUtil.loadImageResource(CardEffectRenderer.class, "/holo_icons/wind_bolt.png");
		BufferedImage windSurge = ImageUtil.loadImageResource(CardEffectRenderer.class, "/holo_icons/wind_surge.png");
		return new BufferedImage[] {partyHat, saradominStar, windBolt, windSurge};
	}

	private static BufferedImage sparkleIcon(long seed)
	{
		if (HOLO_SPARKLE_ICONS.length == 0)
		{
			return null;
		}
		int start = (int) Math.floor(sparkleU(seed, 11) * HOLO_SPARKLE_ICONS.length);
		for (int i = 0; i < HOLO_SPARKLE_ICONS.length; i++)
		{
			BufferedImage icon = HOLO_SPARKLE_ICONS[(start + i) % HOLO_SPARKLE_ICONS.length];
			if (icon != null)
			{
				return icon;
			}
		}
		return null;
	}

	private static void drawIconSparkle(Graphics2D g, BufferedImage icon, double cx, double cy, float size,
		Color tint, float alpha, double rotation)
	{
		if (icon == null || size <= 0f || alpha <= 0f)
		{
			return;
		}
		int w = Math.max(1, Math.round(size));
		int h = Math.max(1, Math.round(size * icon.getHeight() / (float) Math.max(1, icon.getWidth())));
		BufferedImage tinted = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D tg = tinted.createGraphics();
		try
		{
			enableQuality(tg);
			tg.drawImage(icon, 0, 0, w, h, null);
		}
		finally
		{
			tg.dispose();
		}
		applyGrayscaleTint(tinted, tint == null ? Color.WHITE : tint);

		Composite oldComposite = g.getComposite();
		AffineTransform oldTransform = g.getTransform();
		try
		{
			g.setComposite(AlphaComposite.SrcAtop.derive(alpha));
			g.translate(cx, cy);
			g.rotate(rotation);
			g.drawImage(tinted, -w / 2, -h / 2, null);
		}
		finally
		{
			g.setTransform(oldTransform);
			g.setComposite(oldComposite);
		}
	}

	private static void drawFallbackSparkle(Graphics2D g, double sx, double sy, float radius, Color core, float alpha)
	{
		g.setComposite(AlphaComposite.SrcAtop.derive(alpha));
		g.setPaint(new java.awt.RadialGradientPaint(
			(float) sx,
			(float) sy,
			radius,
			new float[] {0.0f, 0.32f, 1.0f},
			new Color[] {
				new Color(255, 255, 255, Math.round(alpha * 255f)),
				core,
				new Color(core.getRed(), core.getGreen(), core.getBlue(), 0)
			}));
		g.fillOval(Math.round((float) sx - radius), Math.round((float) sy - radius),
			Math.round(radius * 2.0f), Math.round(radius * 2.0f));
		g.setColor(new Color(255, 255, 255, Math.round(alpha * 210f)));
		g.setStroke(new BasicStroke(Math.max(1f, radius * 0.18f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int arm = Math.round(radius * 1.15f);
		int cxp = Math.round((float) sx);
		int cyp = Math.round((float) sy);
		g.drawLine(cxp - arm, cyp, cxp + arm, cyp);
		g.drawLine(cxp, cyp - arm, cxp, cyp + arm);
	}

	private static void drawWarpedImage(Graphics2D g, BufferedImage image, Projection p)
	{
		Shape oldClip = g.getClip();
		Polygon poly = p.polygon();
		try
		{
			g.setClip(poly);
			drawHorizontalWarpStrips(g, image, p);
		}
		finally
		{
			g.setClip(oldClip);
		}
	}

	private static void drawHorizontalWarpStrips(Graphics2D g, BufferedImage image, Projection p)
	{
		for (int sy = 0; sy < image.getHeight(); sy += STRIP_HEIGHT)
		{
			int sh = Math.min(STRIP_HEIGHT, image.getHeight() - sy);
			double t0 = sy / (double) image.getHeight();
			double t1 = (sy + sh) / (double) image.getHeight();
			double tm = (t0 + t1) * 0.5d;
			double leftX = lerp(p.x[0], p.x[3], tm);
			double rightX = lerp(p.x[1], p.x[2], tm);
			double leftY = lerp(p.y[0], p.y[3], tm);
			double rightY = lerp(p.y[1], p.y[2], tm);
			double dx = rightX - leftX;
			double dy = rightY - leftY;
			double length = Math.hypot(dx, dy);
			if (length <= 0.5d)
			{
				continue;
			}
			double leftNextY = lerp(p.y[0], p.y[3], t1);
			double rightNextY = lerp(p.y[1], p.y[2], t1);
			double projectedHeight = Math.max(1.0d, Math.abs(((leftNextY + rightNextY) * 0.5d) - ((leftY + rightY) * 0.5d)) + 1.0d);
			AffineTransform oldTransform = g.getTransform();
			try
			{
				g.translate(leftX, leftY);
				g.rotate(Math.atan2(dy, dx));
				g.scale(length / image.getWidth(), projectedHeight / sh);
				g.drawImage(image, 0, 0, image.getWidth(), sh + 1, 0, sy, image.getWidth(), sy + sh, null);
			}
			finally
			{
				g.setTransform(oldTransform);
			}
		}
	}

	private static void drawShadow(Graphics2D g, Projection p, double strength)
	{
		if (strength <= 0.01d)
		{
			return;
		}
		int[] sx = new int[4];
		int[] sy = new int[4];
		int off = (int) Math.round(5.0d + 8.0d * strength);
		for (int i = 0; i < 4; i++)
		{
			sx[i] = p.x[i] + off;
			sy[i] = p.y[i] + off;
		}
		g.setComposite(AlphaComposite.SrcOver.derive((float) (0.08d + 0.10d * strength)));
		g.setColor(Color.BLACK);
		g.fillPolygon(sx, sy, 4);
		g.setComposite(AlphaComposite.SrcOver);
	}

	private static void drawDepthEdge(Graphics2D g, Projection p, double tiltX, double tiltY, Color rarityColor, double strength)
	{
		if (strength <= 0.01d)
		{
			return;
		}
		int offX = (int) Math.round(-tiltY * 0.22d);
		int offY = (int) Math.round(tiltX * 0.16d + 3.0d);
		if (offX == 0 && offY == 0)
		{
			return;
		}
		int[] bx = new int[4];
		int[] by = new int[4];
		for (int i = 0; i < 4; i++)
		{
			bx[i] = p.x[i] + offX;
			by[i] = p.y[i] + offY;
		}
		Color edge = blend(Color.BLACK, rarityColor == null ? Color.WHITE : rarityColor, 0.22f);
		g.setComposite(AlphaComposite.SrcOver.derive((float) (0.34d * strength)));
		g.setColor(edge);
		for (int i = 0; i < 4; i++)
		{
			int j = (i + 1) % 4;
			int edgeDx = p.x[j] - p.x[i];
			int edgeDy = p.y[j] - p.y[i];
			double outward = edgeDx * offY - edgeDy * offX;
			if (outward <= 0.0d)
			{
				continue;
			}
			g.fillPolygon(
				new int[] {p.x[i], p.x[j], bx[j], bx[i]},
				new int[] {p.y[i], p.y[j], by[j], by[i]},
				4);
		}
		g.setComposite(AlphaComposite.SrcOver);
	}

	private static void drawGlare(Graphics2D g, Projection p, double nx, double ny, double strength)
	{
		if (strength <= 0.01d)
		{
			return;
		}
		Shape oldClip = g.getClip();
		Composite oldComposite = g.getComposite();
		java.awt.Paint oldPaint = g.getPaint();
		java.awt.Stroke oldStroke = g.getStroke();
		try
		{
			Polygon poly = p.polygon();
			g.setClip(poly);
			Rectangle b = poly.getBounds();
			float alpha = (float) ((0.08d + 0.18d * strength) * 0.70d);
			g.setComposite(AlphaComposite.SrcOver.derive(alpha));
			double diag = Math.hypot(b.width, b.height);
			double cx = b.getCenterX() + nx * b.width * 0.18d;
			double cy = b.getCenterY() + ny * b.height * 0.10d;
			double bandW = Math.max(10.0d, Math.min(b.width, b.height) * 0.22d);
			double angle = Math.toRadians(-28.0d);

			double lineDx = Math.sin(angle) * diag;
			double lineDy = -Math.cos(angle) * diag;
			int x1 = (int) Math.round(cx - lineDx);
			int y1 = (int) Math.round(cy - lineDy);
			int x2 = (int) Math.round(cx + lineDx);
			int y2 = (int) Math.round(cy + lineDy);
			float gx1 = (float) (cx - Math.cos(angle) * bandW * 0.75d);
			float gy1 = (float) (cy - Math.sin(angle) * bandW * 0.75d);
			float gx2 = (float) (cx + Math.cos(angle) * bandW * 0.75d);
			float gy2 = (float) (cy + Math.sin(angle) * bandW * 0.75d);
			g.setPaint(new GradientPaint(gx1, gy1, new Color(255, 255, 255, 0),
				gx2, gy2, new Color(255, 255, 255, 170), false));
			g.setStroke(new BasicStroke((float) bandW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(x1, y1, x2, y2);
		}
		finally
		{
			g.setStroke(oldStroke);
			g.setPaint(oldPaint);
			g.setComposite(oldComposite);
			g.setClip(oldClip);
		}
	}

	private static void enableQuality(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}

	private static void applyCardAlphaMask(Graphics2D g, int width, int height)
	{
		Composite oldComposite = g.getComposite();
		try
		{
			int arc = Math.max(6, (int) Math.round(12.0d * width / SharedCardRenderer.DEFAULT_CARD_WIDTH));
			g.setComposite(AlphaComposite.DstIn);
			g.setColor(Color.WHITE);
			g.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
		}
		finally
		{
			g.setComposite(oldComposite);
		}
	}

	private static Color withAlpha(Color c, float alpha)
	{
		Color base = c == null ? Color.WHITE : c;
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return new Color(base.getRed(), base.getGreen(), base.getBlue(), a);
	}

	private static Color sparkleTint(long seed, float alpha)
	{
		float hue = (float) sparkleU(seed, 8);
		float saturation = (float) (0.58d + sparkleU(seed, 12) * 0.34d);
		float brightness = (float) (0.82d + sparkleU(seed, 13) * 0.18d);
		Color rgb = Color.getHSBColor((hue + 0.58f) % 1.0f, saturation, brightness);
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return new Color(rgb.getRed(), rgb.getGreen(), rgb.getBlue(), a);
	}

	private static void applyGrayscaleTint(BufferedImage image, Color tint)
	{
		if (image == null)
		{
			return;
		}
		Color color = tint == null ? Color.WHITE : tint;
		int bg = averageCornerColor(image);
		int bgR = (bg >>> 16) & 0xFF;
		int bgG = (bg >>> 8) & 0xFF;
		int bgB = bg & 0xFF;
		boolean keyOpaqueBackground = averageCornerAlpha(image) > 245;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				int argb = image.getRGB(x, y);
				int a = (argb >>> 24) & 0xFF;
				int r = (argb >>> 16) & 0xFF;
				int g = (argb >>> 8) & 0xFF;
				int b = argb & 0xFF;
				if (keyOpaqueBackground)
				{
					double distance = Math.sqrt(
						(r - bgR) * (double) (r - bgR)
							+ (g - bgG) * (double) (g - bgG)
							+ (b - bgB) * (double) (b - bgB));
					double keyed = clamp((distance - 18.0d) / 72.0d, 0.0d, 1.0d);
					keyed = keyed * keyed * (3.0d - keyed * 2.0d);
					a = Math.round((float) (a * keyed));
				}
				if (a <= 0)
				{
					image.setRGB(x, y, 0);
					continue;
				}
				int gray = Math.round(r * 0.299f + g * 0.587f + b * 0.114f);
				float intensity = 0.40f + (gray / 255f) * 0.60f;
				int tr = Math.min(255, Math.round(color.getRed() * intensity + 255f * (1f - intensity) * 0.18f));
				int tg = Math.min(255, Math.round(color.getGreen() * intensity + 255f * (1f - intensity) * 0.18f));
				int tb = Math.min(255, Math.round(color.getBlue() * intensity + 255f * (1f - intensity) * 0.18f));
				image.setRGB(x, y, (a << 24) | (tr << 16) | (tg << 8) | tb);
			}
		}
	}

	private static int averageCornerAlpha(BufferedImage image)
	{
		int w = image.getWidth();
		int h = image.getHeight();
		if (w <= 0 || h <= 0)
		{
			return 0;
		}
		int[] samples = {
			image.getRGB(0, 0),
			image.getRGB(w - 1, 0),
			image.getRGB(0, h - 1),
			image.getRGB(w - 1, h - 1)
		};
		int total = 0;
		for (int sample : samples)
		{
			total += (sample >>> 24) & 0xFF;
		}
		return total / samples.length;
	}

	private static int averageCornerColor(BufferedImage image)
	{
		int w = image.getWidth();
		int h = image.getHeight();
		if (w <= 0 || h <= 0)
		{
			return 0;
		}
		int[] samples = {
			image.getRGB(0, 0),
			image.getRGB(w - 1, 0),
			image.getRGB(0, h - 1),
			image.getRGB(w - 1, h - 1)
		};
		int r = 0;
		int g = 0;
		int b = 0;
		for (int sample : samples)
		{
			r += (sample >>> 16) & 0xFF;
			g += (sample >>> 8) & 0xFF;
			b += sample & 0xFF;
		}
		return ((r / samples.length) << 16) | ((g / samples.length) << 8) | (b / samples.length);
	}

	private static long sparkleSeed(int x, int y, int layer)
	{
		long z = 0x9E3779B97F4A7C15L;
		z ^= (long) x * 0xBF58476D1CE4E5B9L;
		z ^= (long) y * 0x94D049BB133111EBL;
		z ^= (long) layer * 0xD6E8FEB86659FD93L;
		return z;
	}

	private static double sparkleU(long seed, int salt)
	{
		long z = seed + (long) salt * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return ((z >>> 11) & ((1L << 53) - 1)) * 0x1.0p-53;
	}

	private static Color blend(Color a, Color b, float t)
	{
		float u = Math.max(0f, Math.min(1f, t));
		Color ca = a == null ? Color.BLACK : a;
		Color cb = b == null ? Color.WHITE : b;
		return new Color(
			Math.round(ca.getRed() * (1f - u) + cb.getRed() * u),
			Math.round(ca.getGreen() * (1f - u) + cb.getGreen() * u),
			Math.round(ca.getBlue() * (1f - u) + cb.getBlue() * u)
		);
	}

	private static double lerp(double a, double b, double t)
	{
		return a + (b - a) * t;
	}

	private static double clamp01(double v)
	{
		return clamp(v, 0.0d, 1.0d);
	}

	private static double clamp(double v, double min, double max)
	{
		return Math.max(min, Math.min(max, v));
	}

	private static final class Projection
	{
		private final int[] x;
		private final int[] y;

		private Projection(int[] x, int[] y)
		{
			this.x = x;
			this.y = y;
		}

		private Polygon polygon()
		{
			return new Polygon(x, y, 4);
		}
	}

	public static final class Options
	{
		private final boolean shadow;
		private final boolean depth;
		private final boolean glare;
		private final boolean holographic;

		public Options(boolean shadow, boolean depth, boolean glare, boolean holographic)
		{
			this.shadow = shadow;
			this.depth = depth;
			this.glare = glare;
			this.holographic = holographic;
		}

		public static Options allEnabled()
		{
			return new Options(true, true, true, true);
		}
	}
}

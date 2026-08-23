package com.osrstcg.ui.card;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Foil tint, sheen, and live sparkles for {@link CardFxPainter}. */
final class FoilPainter
{
	private FoilPainter()
	{
	}

	/**
	 * Inspect-style foil tint + sheen (no sparkles). Sparkles are drawn live via
	 * {@link #drawAnimatedSparkles}.
	 *
	 * @param cornerRadius outer card radius in px
	 * @param scale        {@code width / 180}
	 */
	static void drawFoil(BufferedImage face, double cornerRadius, double scale, FoilFx fx)
	{
		if (face == null || fx == null)
		{
			return;
		}
		int w = face.getWidth();
		int h = face.getHeight();
		Shape card = new RoundRectangle2D.Double(0, 0, w, h, cornerRadius * 2.0d, cornerRadius * 2.0d);

		double hue = fx.getTintHue();
		Color c1 = CardColorMath.hsla(hue, fx.getTintSat(), 0.58d, 0.2d);
		Color c2 = CardColorMath.hsla(hue + 70.0d, fx.getTintSatMid(), 0.68d, 0.28d);
		Color c3 = CardColorMath.hsla(hue + 150.0d, fx.getTintSatEnd(), 0.62d, 0.18d);

		// CSS: c1 0% → transparent 32% → c2 48% → transparent 62% → c3 100%.
		BufferedImage tint = CardFxPainter.newLayer(w, h);
		Graphics2D tg = CardFxPainter.quality(tint);
		try
		{
			tg.setPaint(CardFxPainter.cssLinearGradient(w, h, fx.getTintAngle(), 2.6d,
				new float[]{0f, 0.32f, 0.48f, 0.62f, 1f},
				new Color[]{c1, CardFxPainter.transparent(c1), c2, CardFxPainter.transparent(c3), c3}));
			tg.fill(card);
		}
		finally
		{
			tg.dispose();
		}
		CardFxPainter.blend(face, tint, CardFxPainter.BlendMode.COLOR_DODGE, 1.0d);

		Color warm = new Color(255, 240, 200);
		Color cool = new Color(180, 230, 255);
		BufferedImage sheen = CardFxPainter.newLayer(w, h);
		Graphics2D sg = CardFxPainter.quality(sheen);
		try
		{
			// Inspect sheen: transparent 22% → warm 40% → cool 60% → transparent 78%.
			sg.setPaint(CardFxPainter.cssLinearGradient(w, h, 115.0d, 2.2d,
				new float[]{0f, 0.22f, 0.40f, 0.60f, 0.78f, 1f},
				new Color[]{
					CardFxPainter.alpha(warm, 0.0d), CardFxPainter.alpha(warm, 0.0d), CardFxPainter.alpha(warm, 0.14d),
					CardFxPainter.alpha(cool, 0.12d), CardFxPainter.alpha(cool, 0.0d), CardFxPainter.alpha(cool, 0.0d)}));
			sg.fill(card);
		}
		finally
		{
			sg.dispose();
		}
		CardFxPainter.blend(face, sheen, CardFxPainter.BlendMode.SOFT_LIGHT, 1.0d);
	}

	/**
	 * Live foil sparkles matching {@code .album-card__sparkle} / {@code album-foil-sparkle}:
	 * centered circles at {@code (x%, y%)} of the full card (including gold frame),
	 * diameter {@code size * scale}, color {@code hsl(hue, sat, light)}, soft glow ≈ {@code 0 0 0.25em}.
	 *
	 * @param scale  {@code width / 180} (design width 180px at scale 1)
	 * @param timeSec shared animation clock in seconds (same for every sparkle on the card)
	 */
	static void drawAnimatedSparkles(Graphics2D g, int x, int y, int w, int h, double cornerRadius,
		double scale, FoilFx fx, double timeSec)
	{
		if (g == null || fx == null || w < 2 || h < 2)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			Shape card = new RoundRectangle2D.Double(x, y, w, h, cornerRadius * 2.0d, cornerRadius * 2.0d);
			g2.clip(card);

			double glow = 0.25d * CardFxPainter.CARD_EM_PX * scale;
			for (FoilFx.Sparkle s : fx.getSparkles())
			{
				FoilSparkleAnimation.Sample anim = FoilSparkleAnimation.sample(s.getDelay(), s.getDuration(), timeSec);
				if (anim.getOpacity() <= 0.001d)
				{
					continue;
				}
				double d = Math.max(0.5d, s.getSize() * scale * anim.getScale());
				double cx = x + s.getX() / 100.0d * w;
				double cy = y + s.getY() / 100.0d * h;
				Color core = CardColorMath.hsla(s.getHue(), s.getSat(), s.getLight(), anim.getOpacity());
				double outer = d / 2.0d + glow;
				float coreFrac = (float) Math.max(0.05d, Math.min(0.9d, (d / 2.0d) / outer));
				float midFrac = coreFrac + (1f - coreFrac) * 0.3f;
				g2.setPaint(new RadialGradientPaint(
					new Point2D.Double(cx, cy), (float) outer,
					new float[]{0f, coreFrac, midFrac, 1f},
					new Color[]{core, core, CardFxPainter.alpha(core, anim.getOpacity() * 0.28d), CardFxPainter.alpha(core, 0.0d)},
					MultipleGradientPaint.CycleMethod.NO_CYCLE));
				g2.fill(new java.awt.geom.Ellipse2D.Double(cx - outer, cy - outer, outer * 2.0d, outer * 2.0d));
			}
		}
		finally
		{
			g2.dispose();
		}
	}
}

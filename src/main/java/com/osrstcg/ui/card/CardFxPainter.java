package com.osrstcg.ui.card;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferInt;
import java.awt.image.Kernel;

/**
 * Foil and wear compositing for the card face, approximating the website's CSS blend modes
 * ({@code color-dodge}, {@code soft-light}, {@code multiply}, {@code luminosity}) and the
 * {@code saturate/contrast/brightness} filter with direct pixel math on an offscreen ARGB raster.
 *
 * <p>Wear blend modes are applied onto the face so multiply dirt/stains etch into the art the way
 * they read in inspect. Foil sparkles are drawn live (not baked into the face raster).</p>
 *
 * <p>All geometry constants mirror {@code .album-card__foil-*} and {@code .card-inspect__wear*}
 * in {@code osrs-tcg-front/src/index.css}.</p>
 */
public final class CardFxPainter
{
	/** Root font size of {@code .album-card} in design px; {@code 1em} for CSS values below. */
	static final double CARD_EM_PX = 12.5d;
	private static final double BEZIER_K = 0.5522847498307936d;

	public enum BlendMode
	{
		NORMAL,
		MULTIPLY,
		SOFT_LIGHT,
		COLOR_DODGE,
		LUMINOSITY
	}

	private CardFxPainter()
	{
	}

	public static BufferedImage newLayer(int width, int height)
	{
		return new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
	}

	private static int[] pixels(BufferedImage img)
	{
		return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
	}

	static Graphics2D quality(BufferedImage img)
	{
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g;
	}

	// ------------------------------------------------------------------ blending

	/**
	 * Composites {@code layer} onto {@code base} with a CSS {@code mix-blend-mode}.
	 *
	 * @param writeIntoTransparent when {@code true}, empty base pixels receive the source (isolated-group
	 *                             backdrop). When {@code false}, transparent base is skipped so FX stay
	 *                             clipped to the opaque card silhouette.
	 */
	public static void blend(BufferedImage base, BufferedImage layer, BlendMode mode, double opacity,
		boolean writeIntoTransparent)
	{
		if (base == null || layer == null || opacity <= 0.0d)
		{
			return;
		}
		int[] dst = pixels(base);
		int[] src = pixels(layer);
		int n = Math.min(dst.length, src.length);
		double op = Math.min(1.0d, opacity);
		double[] blended = new double[3];

		for (int i = 0; i < n; i++)
		{
			int s = src[i];
			int sa = s >>> 24;
			if (sa == 0)
			{
				continue;
			}
			int d = dst[i];
			int da = d >>> 24;

			double f = (sa / 255.0d) * op;
			double sr = ((s >> 16) & 0xFF) / 255.0d;
			double sg = ((s >> 8) & 0xFF) / 255.0d;
			double sb = (s & 0xFF) / 255.0d;

			if (da == 0)
			{
				if (!writeIntoTransparent)
				{
					continue;
				}
				int outA = to255(f);
				if (outA <= 0)
				{
					continue;
				}
				dst[i] = (outA << 24) | (to255(sr) << 16) | (to255(sg) << 8) | to255(sb);
				continue;
			}

			double br = ((d >> 16) & 0xFF) / 255.0d;
			double bg = ((d >> 8) & 0xFF) / 255.0d;
			double bb = (d & 0xFF) / 255.0d;

			applyMode(mode, br, bg, bb, sr, sg, sb, blended);
			int r = to255(br + (blended[0] - br) * f);
			int g = to255(bg + (blended[1] - bg) * f);
			int b = to255(bb + (blended[2] - bb) * f);
			dst[i] = (da << 24) | (r << 16) | (g << 8) | b;
		}
	}

	/** Clip to opaque card pixels (final FX → face). */
	public static void blend(BufferedImage base, BufferedImage layer, BlendMode mode, double opacity)
	{
		blend(base, layer, mode, opacity, false);
	}

	private static void applyMode(BlendMode mode, double br, double bg, double bb, double sr, double sg, double sb,
		double[] out)
	{
		switch (mode)
		{
			case MULTIPLY:
				out[0] = br * sr;
				out[1] = bg * sg;
				out[2] = bb * sb;
				return;
			case SOFT_LIGHT:
				out[0] = softLight(br, sr);
				out[1] = softLight(bg, sg);
				out[2] = softLight(bb, sb);
				return;
			case COLOR_DODGE:
				out[0] = colorDodge(br, sr);
				out[1] = colorDodge(bg, sg);
				out[2] = colorDodge(bb, sb);
				return;
			case LUMINOSITY:
				setLuminosity(br, bg, bb, luminosity(sr, sg, sb), out);
				return;
			case NORMAL:
			default:
				out[0] = sr;
				out[1] = sg;
				out[2] = sb;
		}
	}

	private static double colorDodge(double cb, double cs)
	{
		if (cb <= 0.0d)
		{
			return 0.0d;
		}
		if (cs >= 1.0d)
		{
			return 1.0d;
		}
		return Math.min(1.0d, cb / (1.0d - cs));
	}

	private static double softLight(double cb, double cs)
	{
		if (cs <= 0.5d)
		{
			return cb - (1.0d - 2.0d * cs) * cb * (1.0d - cb);
		}
		double d = cb <= 0.25d ? ((16.0d * cb - 12.0d) * cb + 4.0d) * cb : Math.sqrt(cb);
		return cb + (2.0d * cs - 1.0d) * (d - cb);
	}

	private static double luminosity(double r, double g, double b)
	{
		return 0.3d * r + 0.59d * g + 0.11d * b;
	}

	private static void setLuminosity(double r, double g, double b, double targetLum, double[] out)
	{
		double d = targetLum - luminosity(r, g, b);
		double nr = r + d;
		double ng = g + d;
		double nb = b + d;

		double lum = luminosity(nr, ng, nb);
		double min = Math.min(nr, Math.min(ng, nb));
		double max = Math.max(nr, Math.max(ng, nb));
		if (min < 0.0d && lum != min)
		{
			double k = lum / (lum - min);
			nr = lum + (nr - lum) * k;
			ng = lum + (ng - lum) * k;
			nb = lum + (nb - lum) * k;
		}
		if (max > 1.0d && max != lum)
		{
			double k = (1.0d - lum) / (max - lum);
			nr = lum + (nr - lum) * k;
			ng = lum + (ng - lum) * k;
			nb = lum + (nb - lum) * k;
		}
		out[0] = nr;
		out[1] = ng;
		out[2] = nb;
	}

	private static int to255(double v)
	{
		int i = (int) Math.round(v * 255.0d);
		return i < 0 ? 0 : Math.min(i, 255);
	}

	// ------------------------------------------------------------------ whole-card filter

	/**
	 * {@code saturate(1 - fade*0.72) contrast(1 - fade*0.08) brightness(1 - fade*0.06)} -
	 * the filter the site puts on {@code .card-inspect__card.has-wear .album-card}.
	 */
	public static void applyWearFilter(BufferedImage base, double fade)
	{
		if (base == null || fade <= 0.0d)
		{
			return;
		}
		double sat = 1.0d - fade * 0.72d;
		double contrast = 1.0d - fade * 0.08d;
		double brightness = 1.0d - fade * 0.06d;

		double rr = 0.213d + 0.787d * sat;
		double rg = 0.715d - 0.715d * sat;
		double rb = 0.072d - 0.072d * sat;
		double gr = 0.213d - 0.213d * sat;
		double gg = 0.715d + 0.285d * sat;
		double gb = 0.072d - 0.072d * sat;
		double bbr = 0.213d - 0.213d * sat;
		double bg = 0.715d - 0.715d * sat;
		double bb = 0.072d + 0.928d * sat;

		int[] px = pixels(base);
		for (int i = 0; i < px.length; i++)
		{
			int p = px[i];
			int a = p >>> 24;
			if (a == 0)
			{
				continue;
			}
			double r = ((p >> 16) & 0xFF) / 255.0d;
			double g = ((p >> 8) & 0xFF) / 255.0d;
			double b = (p & 0xFF) / 255.0d;

			double nr = rr * r + rg * g + rb * b;
			double ng = gr * r + gg * g + gb * b;
			double nb = bbr * r + bg * g + bb * b;

			nr = ((nr - 0.5d) * contrast + 0.5d) * brightness;
			ng = ((ng - 0.5d) * contrast + 0.5d) * brightness;
			nb = ((nb - 0.5d) * contrast + 0.5d) * brightness;

			px[i] = (a << 24) | (to255(nr) << 16) | (to255(ng) << 8) | to255(nb);
		}
	}

	// ------------------------------------------------------------------ foil

	/**
	 * Live foil sparkles matching {@code .album-card__sparkle} / {@code album-foil-sparkle}:
	 * centered circles at {@code (x%, y%)} of the full card (including gold frame),
	 * diameter {@code size * scale}, color {@code hsl(hue, sat, light)}, soft glow ≈ {@code 0 0 0.25em}.
	 *
	 * @param scale  {@code width / 180} (design width 180px at scale 1)
	 * @param timeSec shared animation clock in seconds (same for every sparkle on the card)
	 */
	public static void drawAnimatedSparkles(Graphics2D g, int x, int y, int w, int h, double cornerRadius,
		double scale, FoilFx fx, double timeSec)
	{
		FoilPainter.drawAnimatedSparkles(g, x, y, w, h, cornerRadius, scale, fx, timeSec);
	}

	// ------------------------------------------------------------------ wear

	/**
	 * Wear layers in CSS paint order: color wash → grime → edges → scratches → spots.
	 * Call {@link #applyWearFilter} on the face (and foil) first.
	 *
	 * <p>Unlike foil, wear blend modes are applied directly onto the card face. Scratches already
	 * matched the site with this path; isolating the wear group flattened multiply dirt/stains.</p>
	 */
	public static void drawWear(BufferedImage face, double cornerRadius, WearFx wear)
	{
		if (face == null || wear == null)
		{
			return;
		}
		int w = face.getWidth();
		int h = face.getHeight();
		Shape card = new RoundRectangle2D.Double(0, 0, w, h, cornerRadius * 2.0d, cornerRadius * 2.0d);

		drawColorWash(face, card, w, h, wear.getFade());
		drawGrime(face, card, w, h, wear);
		if (wear.isShowEdges())
		{
			applyEdges(face, w, h, wear);
		}
		drawScratches(face, card, w, h, wear);
		drawSpots(face, card, w, h, wear);
	}

	private static void drawColorWash(BufferedImage face, Shape card, int w, int h, double fade)
	{
		Color light = new Color(255, 255, 255);
		Color dark = new Color(26, 26, 26);
		BufferedImage layer = newLayer(w, h);
		Graphics2D g = quality(layer);
		try
		{
			g.setPaint(cssLinearGradient(w, h, 160.0d, 1.0d,
				new float[]{0f, 0.40f, 0.4001f, 1f},
				new Color[]{alpha(light, 0.04d * fade), alpha(light, 0.0d), alpha(dark, 0.0d), alpha(dark, 0.22d * fade)}));
			g.fill(card);
		}
		finally
		{
			g.dispose();
		}
		blend(face, layer, BlendMode.LUMINOSITY, 0.35d + fade * 0.65d);
	}

	private static void drawGrime(BufferedImage face, Shape card, int w, int h, WearFx wear)
	{
		double i = wear.getIntensity();
		double dirt = wear.getDirtMix();
		BufferedImage layer = newLayer(w, h);
		Graphics2D g = quality(layer);
		try
		{
			g.clip(card);
			radialEllipse(g, w, h, 0.55d, 0.40d, 0.50d, 0.40d, new Color(32, 32, 32), 0.14d * i * dirt, 0.75d);
			radialEllipse(g, w, h, 0.78d, 0.70d, 0.35d, 0.28d, new Color(18, 18, 18), 0.32d * i * dirt, 0.70d);
			radialEllipse(g, w, h, 0.18d, 0.22d, 0.40d, 0.30d, new Color(26, 26, 26), 0.34d * i * dirt, 0.70d);
		}
		finally
		{
			g.dispose();
		}
		double opacity = wear.getGrade() == CardGrade.A
			? 0.45d
			: (0.3d + i * 0.7d) * (0.35d + dirt * 0.65d);
		blend(face, layer, BlendMode.MULTIPLY, opacity);
	}

	private static void radialEllipse(Graphics2D g, int w, int h, double cxPct, double cyPct, double rxPct, double ryPct,
		Color color, double alpha, double endStop)
	{
		double cx = cxPct * w;
		double cy = cyPct * h;
		double rx = Math.max(1.0d, rxPct * w);
		double ry = Math.max(1.0d, ryPct * h);
		AffineTransform tx = new AffineTransform();
		tx.translate(cx, cy);
		tx.scale(1.0d, ry / rx);
		tx.translate(-cx, -cy);
		g.setPaint(new RadialGradientPaint(
			new Point2D.Double(cx, cy), (float) rx, new Point2D.Double(cx, cy),
			new float[]{0f, (float) endStop},
			new Color[]{alpha(color, alpha), alpha(color, 0.0d)},
			MultipleGradientPaint.CycleMethod.NO_CYCLE,
			MultipleGradientPaint.ColorSpaceType.SRGB,
			tx));
		g.fill(new Rectangle2D.Double(0, 0, w, h));
	}

	/**
	 * Inset dual box-shadow (white ring over black) approximated per pixel from the distance to the
	 * nearest edge, using the logistic approximation of a Gaussian shadow profile.
	 */
	private static void applyEdges(BufferedImage face, int w, int h, WearFx wear)
	{
		double i = wear.getIntensity();
		double e = wear.getEdgeMix();
		double whiteBlur;
		double whiteAlpha;
		double blackBlur;
		double blackAlpha;
		double ringBlur = 0.0d;
		double ringAlpha = 0.0d;
		double opacity;

		if (wear.getGrade() == CardGrade.E)
		{
			whiteBlur = 15.0d;
			whiteAlpha = 0.30d;
			blackBlur = 30.0d;
			blackAlpha = 0.45d;
			ringBlur = 2.0d;
			ringAlpha = 0.35d;
			opacity = (0.45d + i * 0.55d) * (0.35d + e * 0.65d);
		}
		else
		{
			whiteBlur = 12.0d * i * e;
			whiteAlpha = 0.26d * i * e;
			blackBlur = 24.0d * i * e;
			blackAlpha = 0.34d * i * e;
			opacity = (0.45d + i * 0.55d) * (0.35d + e * 0.65d);
		}

		int maxDepth = Math.max(1, Math.min(w, h) / 2 + 1);
		double[] whiteLut = shadowFalloff(maxDepth, whiteBlur, whiteAlpha * opacity);
		double[] blackLut = shadowFalloff(maxDepth, blackBlur, blackAlpha * opacity);
		double[] ringLut = ringAlpha > 0.0d ? shadowFalloff(maxDepth, ringBlur, ringAlpha * opacity) : null;

		int[] px = pixels(face);
		for (int y = 0; y < h; y++)
		{
			int rowMin = Math.min(y, h - 1 - y);
			int row = y * w;
			for (int x = 0; x < w; x++)
			{
				int p = px[row + x];
				int a = p >>> 24;
				if (a == 0)
				{
					continue;
				}
				int d = Math.min(rowMin, Math.min(x, w - 1 - x));
				if (d >= maxDepth)
				{
					continue;
				}
				double wa = whiteLut[d];
				double ba = blackLut[d];
				double ra = ringLut == null ? 0.0d : ringLut[d];
				if (wa <= 0.001d && ba <= 0.001d && ra <= 0.001d)
				{
					continue;
				}

				double r = ((p >> 16) & 0xFF) / 255.0d;
				double g = ((p >> 8) & 0xFF) / 255.0d;
				double b = (p & 0xFF) / 255.0d;
				r = r * (1 - ba);
				g = g * (1 - ba);
				b = b * (1 - ba);
				r = r + (1.0d - r) * wa;
				g = g + (1.0d - g) * wa;
				b = b + (1.0d - b) * wa;
				if (ra > 0.0d)
				{
					r = r + (1.0d - r) * ra;
					g = g + (1.0d - g) * ra;
					b = b + (1.0d - b) * ra;
				}
				px[row + x] = (a << 24) | (to255(r) << 16) | (to255(g) << 8) | to255(b);
			}
		}
	}

	private static double[] shadowFalloff(int size, double blur, double peakAlpha)
	{
		double[] lut = new double[size];
		if (peakAlpha <= 0.0d)
		{
			return lut;
		}
		if (blur <= 0.0d)
		{
			lut[0] = peakAlpha;
			return lut;
		}
		double sigma = blur / 2.0d;
		for (int d = 0; d < size; d++)
		{
			// 1 - Phi(d/sigma), logistic approximation of the Gaussian CDF: 0.5 at the edge, ~0.03 at d = blur.
			lut[d] = peakAlpha * 2.0d / (1.0d + Math.exp(1.702d * d / sigma));
		}
		return lut;
	}

	private static void drawScratches(BufferedImage face, Shape card, int w, int h, WearFx wear)
	{
		if (wear.getScratches().isEmpty())
		{
			return;
		}
		boolean thick = wear.getGrade() == CardGrade.D || wear.getGrade() == CardGrade.E;
		double thickness = thick ? 1.35d : 1.1d;

		BufferedImage layer = newLayer(w, h);
		Graphics2D g = quality(layer);
		try
		{
			g.clip(card);
			for (WearFx.Scratch s : wear.getScratches())
			{
				double len = Math.max(1.0d, s.getLen() / 100.0d * w);
				double cx = s.getX() / 100.0d * w;
				double cy = s.getY() / 100.0d * h;
				double op = Math.max(0.0d, Math.min(1.0d, s.getOpacity()));

				AffineTransform saved = g.getTransform();
				g.translate(cx, cy);
				g.rotate(Math.toRadians(s.getAngle()));
				g.translate(-len / 2.0d, -thickness / 2.0d);

				Shape bar = new RoundRectangle2D.Double(0, 0, len, thickness, 2, 2);
				g.setColor(alpha(Color.BLACK, 0.28d * op));
				g.translate(0, 0.5d);
				g.fill(bar);
				g.translate(0, -0.5d);

				g.setPaint(new LinearGradientPaint(
					new Point2D.Double(0, 0), new Point2D.Double(len, 0),
					new float[]{0f, 0.20f, 0.55f, 1f},
					new Color[]{
						alpha(Color.WHITE, 0.0d), alpha(Color.WHITE, 0.6d * op),
						alpha(Color.WHITE, 0.22d * op), alpha(Color.WHITE, 0.0d)},
					MultipleGradientPaint.CycleMethod.NO_CYCLE));
				g.fill(bar);
				g.setTransform(saved);
			}
		}
		finally
		{
			g.dispose();
		}
		blend(face, layer, BlendMode.SOFT_LIGHT, 1.0d);
	}

	private static void drawSpots(BufferedImage face, Shape card, int w, int h, WearFx wear)
	{
		if (wear.getSpots().isEmpty())
		{
			return;
		}
		BufferedImage sharp = newLayer(w, h);
		Graphics2D gs = quality(sharp);
		try
		{
			gs.clip(card);
			for (WearFx.Spot spot : wear.getSpots())
			{
				if (spot.getBlur() > 0.0d)
				{
					BufferedImage soft = newLayer(w, h);
					Graphics2D gb = quality(soft);
					try
					{
						gb.clip(card);
						paintSpot(gb, w, h, spot);
					}
					finally
					{
						gb.dispose();
					}
					blend(face, softenLayer(soft, spot.getBlur()), BlendMode.MULTIPLY, 1.0d);
				}
				else
				{
					paintSpot(gs, w, h, spot);
				}
			}
		}
		finally
		{
			gs.dispose();
		}
		blend(face, sharp, BlendMode.MULTIPLY, 1.0d);
	}

	private static void paintSpot(Graphics2D g, int w, int h, WearFx.Spot spot)
	{
		double sw = Math.max(1.0d, spot.getW() / 100.0d * w);
		double sh = Math.max(1.0d, spot.getH() / 100.0d * h);
		double cx = spot.getX() / 100.0d * w;
		double cy = spot.getY() / 100.0d * h;
		double op = Math.max(0.0d, Math.min(1.0d, spot.getOpacity()));

		AffineTransform saved = g.getTransform();
		Shape savedClip = g.getClip();
		try
		{
			g.translate(cx, cy);
			g.rotate(Math.toRadians(spot.getRotate()));
			g.translate(-sw / 2.0d, -sh / 2.0d);

			Shape shape = borderRadiusShape(sw, sh, spot.getBorderRadius());
			g.clip(shape);
			g.setPaint(spotPaint(spot.getShape(), sw, sh, op));
			g.fill(shape);
		}
		finally
		{
			g.setClip(savedClip);
			g.setTransform(saved);
		}
	}

	private static java.awt.Paint spotPaint(WearFx.SpotShape shape, double w, double h, double op)
	{
		switch (shape)
		{
			case SMEAR:
				return new LinearGradientPaint(
					new Point2D.Double(0, h / 2.0d), new Point2D.Double(w, h / 2.0d),
					new float[]{0f, 0.18f, 0.50f, 0.82f, 1f},
					new Color[]{
						alpha(new Color(24, 24, 24), 0.0d),
						alpha(new Color(24, 24, 24), 0.55d * op),
						alpha(new Color(18, 18, 18), 0.82d * op),
						alpha(new Color(24, 24, 24), 0.5d * op),
						alpha(new Color(24, 24, 24), 0.0d)},
					MultipleGradientPaint.CycleMethod.NO_CYCLE);
			case ELLIPSE:
				return ellipseGradient(w, h, 0.48d, 0.46d, 0.85d, 0.70d,
					new Color(22, 22, 22), 0.88d * op, new Color(26, 26, 26), 0.32d * op, 0.48f, 0.72f);
			case BLOB:
			case SPLOTCH:
				return ellipseGradient(w, h, 0.44d, 0.40d, 0.72d, 0.68d,
					new Color(20, 20, 20), 0.92d * op, new Color(26, 26, 26), 0.38d * op, 0.42f, 0.74f);
			case ROUND:
			default:
			{
				double r = Math.max(1.0d, Math.hypot(w / 2.0d, h / 2.0d));
				return new RadialGradientPaint(
					new Point2D.Double(w / 2.0d, h / 2.0d), (float) r,
					new float[]{0f, 0.45f, 0.70f},
					new Color[]{
						alpha(new Color(22, 22, 22), 0.9d * op),
						alpha(new Color(26, 26, 26), 0.35d * op),
						alpha(new Color(26, 26, 26), 0.0d)},
					MultipleGradientPaint.CycleMethod.NO_CYCLE);
			}
		}
	}

	private static java.awt.Paint ellipseGradient(double w, double h, double cxPct, double cyPct, double rxPct, double ryPct,
		Color inner, double innerAlpha, Color mid, double midAlpha, float midStop, float endStop)
	{
		double cx = cxPct * w;
		double cy = cyPct * h;
		double rx = Math.max(1.0d, rxPct * w);
		double ry = Math.max(1.0d, ryPct * h);
		AffineTransform tx = new AffineTransform();
		tx.translate(cx, cy);
		tx.scale(1.0d, ry / rx);
		tx.translate(-cx, -cy);
		return new RadialGradientPaint(
			new Point2D.Double(cx, cy), (float) rx, new Point2D.Double(cx, cy),
			new float[]{0f, midStop, endStop},
			new Color[]{alpha(inner, innerAlpha), alpha(mid, midAlpha), alpha(mid, 0.0d)},
			MultipleGradientPaint.CycleMethod.NO_CYCLE,
			MultipleGradientPaint.ColorSpaceType.SRGB,
			tx);
	}

	/** Approximate CSS {@code filter: blur(Npx)} for organic stains; radius drives pass count. */
	private static BufferedImage softenLayer(BufferedImage layer, double blurPx)
	{
		int passes = Math.max(1, (int) Math.round(Math.max(0.2d, blurPx)));
		BufferedImage current = layer;
		float[] kernel = {
			1f / 16f, 2f / 16f, 1f / 16f,
			2f / 16f, 4f / 16f, 2f / 16f,
			1f / 16f, 2f / 16f, 1f / 16f
		};
		ConvolveOp op = new ConvolveOp(new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null);
		for (int i = 0; i < passes; i++)
		{
			current = op.filter(current, newLayer(current.getWidth(), current.getHeight()));
		}
		return current;
	}

	// ------------------------------------------------------------------ shapes / paints

	/**
	 * CSS {@code linear-gradient(angleDeg, …)} over a background box scaled by {@code sizeFactor} and
	 * centered on the element ({@code background-position: 50% 50%}).
	 */
	public static LinearGradientPaint cssLinearGradient(int w, int h, double angleDeg, double sizeFactor,
		float[] fractions, Color[] colors)
	{
		double a = Math.toRadians(angleDeg);
		double dx = Math.sin(a);
		double dy = -Math.cos(a);
		double gw = w * sizeFactor;
		double gh = h * sizeFactor;
		double len = Math.max(1.0d, Math.abs(gw * Math.sin(a)) + Math.abs(gh * Math.cos(a)));
		double cx = w / 2.0d;
		double cy = h / 2.0d;
		return new LinearGradientPaint(
			new Point2D.Double(cx - dx * len / 2.0d, cy - dy * len / 2.0d),
			new Point2D.Double(cx + dx * len / 2.0d, cy + dy * len / 2.0d),
			fractions, colors, MultipleGradientPaint.CycleMethod.NO_CYCLE);
	}

	/**
	 * CSS {@code border-radius} with eight percentages: horizontal TL/TR/BR/BL then vertical TL/TR/BR/BL,
	 * including the overlap scale-down rule.
	 */
	public static Shape borderRadiusShape(double w, double h, double[] radiiPercent)
	{
		double htl = radiiPercent[0] / 100.0d * w;
		double htr = radiiPercent[1] / 100.0d * w;
		double hbr = radiiPercent[2] / 100.0d * w;
		double hbl = radiiPercent[3] / 100.0d * w;
		double vtl = radiiPercent[4] / 100.0d * h;
		double vtr = radiiPercent[5] / 100.0d * h;
		double vbr = radiiPercent[6] / 100.0d * h;
		double vbl = radiiPercent[7] / 100.0d * h;

		double f = 1.0d;
		f = Math.min(f, ratio(w, htl + htr));
		f = Math.min(f, ratio(w, hbl + hbr));
		f = Math.min(f, ratio(h, vtl + vbl));
		f = Math.min(f, ratio(h, vtr + vbr));
		if (f < 1.0d)
		{
			htl *= f;
			htr *= f;
			hbr *= f;
			hbl *= f;
			vtl *= f;
			vtr *= f;
			vbr *= f;
			vbl *= f;
		}

		Path2D.Double p = new Path2D.Double();
		p.moveTo(htl, 0);
		p.lineTo(w - htr, 0);
		p.curveTo(w - htr + htr * BEZIER_K, 0, w, vtr - vtr * BEZIER_K, w, vtr);
		p.lineTo(w, h - vbr);
		p.curveTo(w, h - vbr + vbr * BEZIER_K, w - hbr + hbr * BEZIER_K, h, w - hbr, h);
		p.lineTo(hbl, h);
		p.curveTo(hbl - hbl * BEZIER_K, h, 0, h - vbl + vbl * BEZIER_K, 0, h - vbl);
		p.lineTo(0, vtl);
		p.curveTo(0, vtl - vtl * BEZIER_K, htl - htl * BEZIER_K, 0, htl, 0);
		p.closePath();
		return p;
	}

	private static double ratio(double side, double sum)
	{
		return sum <= 0.0d ? 1.0d : Math.min(1.0d, side / sum);
	}

	/**
	 * A CSS {@code transparent} gradient stop keeps its neighbour's hue because browsers interpolate
	 * premultiplied; AWT does not, so transparent stops must carry the adjacent color.
	 */
	static Color transparent(Color c)
	{
		return alpha(c, 0.0d);
	}

	public static Color alpha(Color c, double a)
	{
		int av = CardColorMath.clamp255((int) Math.round(Math.max(0.0d, Math.min(1.0d, a)) * 255.0d));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), av);
	}
}

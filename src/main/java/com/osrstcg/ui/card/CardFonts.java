package com.osrstcg.ui.card;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the plugin's bundled RuneScape-style fonts and derives sized variants used across card rendering,
 * scaled from a fixed root size (falls back to SansSerif if a font resource is missing or fails to load).
 */
public final class CardFonts
{
	private static final Logger log = LoggerFactory.getLogger(CardFonts.class);

	public static final float ROOT_SIZE_PX = 15.625f;
	public static final float TITLE_EM = 1.12f;
	public static final float FULL_EXAMINE_EM = 0.92f;
	public static final float FULL_SCORE_EM = 1.08f;

	private static final Font REGULAR = load("com/osrstcg/fonts/runescape.ttf", Font.PLAIN);
	private static final Font BOLD = load("com/osrstcg/fonts/runescape_bold.ttf", Font.BOLD);

	private CardFonts()
	{
	}

	/** Regular body font sized to {@code scale} of the root size. */
	public static Font body(double scale)
	{
		return sized(REGULAR, ROOT_SIZE_PX * (float) Math.max(0.01d, scale));
	}

	/** Regular examine-text font sized to {@code scale} of the root size times {@code em}. */
	public static Font examine(double scale, float em)
	{
		float clampedEm = Math.max(0.01f, em);
		return sized(REGULAR, ROOT_SIZE_PX * clampedEm * (float) Math.max(0.01d, scale));
	}

	/** Bold title font sized to {@code scale} of the root size times {@code em}. */
	public static Font title(double scale, float em)
	{
		float clampedEm = Math.max(0.01f, em);
		return sized(BOLD, ROOT_SIZE_PX * clampedEm * (float) Math.max(0.01d, scale));
	}

	/** Bold font sized to {@code scale} of the root size. */
	public static Font bold(double scale)
	{
		return sized(BOLD, ROOT_SIZE_PX * (float) Math.max(0.01d, scale));
	}

	/** Bold title font for full-art cards, sized with {@link #TITLE_EM} and rounded to a whole pixel size. */
	public static Font fullArtTitle(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * TITLE_EM * (float) Math.max(0.01d, scale));
	}

	/** Bold examine-text font for full-art cards, sized with {@link #FULL_EXAMINE_EM} and rounded to a whole pixel size. */
	public static Font fullArtExamine(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * FULL_EXAMINE_EM * (float) Math.max(0.01d, scale));
	}

	/** Bold score font for full-art cards, sized with {@link #FULL_SCORE_EM} and rounded to a whole pixel size. */
	public static Font fullArtScore(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * FULL_SCORE_EM * (float) Math.max(0.01d, scale));
	}

	/** Derives {@code base} at {@code sizePx}, floored at 1px. */
	private static Font sized(Font base, float sizePx)
	{
		float size = Math.max(1f, sizePx);
		return base.deriveFont(size);
	}

	/** Derives {@code base} at {@code sizePx} rounded to the nearest whole pixel, floored at 1px. */
	private static Font sizedFullArt(Font base, float sizePx)
	{
		float size = Math.max(1f, Math.round(sizePx));
		return base.deriveFont(size);
	}

	/** Loads a TrueType font from the given classpath resource, falling back to SansSerif on any failure. */
	private static Font load(String resourcePath, int fallbackStyle)
	{
		try (InputStream in = CardFonts.class.getResourceAsStream("/" + resourcePath))
		{
			if (in == null)
			{
				log.warn("Missing card font resource /{}; falling back to SansSerif", resourcePath);
				return new Font(Font.SANS_SERIF, fallbackStyle, Math.round(ROOT_SIZE_PX));
			}
			Font font = Font.createFont(Font.TRUETYPE_FONT, in);
			return font.deriveFont(ROOT_SIZE_PX);
		}
		catch (FontFormatException | IOException ex)
		{
			log.warn("Failed to load card font /{}; falling back to SansSerif", resourcePath, ex);
			return new Font(Font.SANS_SERIF, fallbackStyle, Math.round(ROOT_SIZE_PX));
		}
	}
}

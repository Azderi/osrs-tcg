package com.osrstcg.ui.card;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	public static Font body(double scale)
	{
		return sized(REGULAR, ROOT_SIZE_PX * (float) Math.max(0.01d, scale));
	}

	public static Font examine(double scale, float em)
	{
		float clampedEm = Math.max(0.01f, em);
		return sized(REGULAR, ROOT_SIZE_PX * clampedEm * (float) Math.max(0.01d, scale));
	}

	public static Font title(double scale, float em)
	{
		float clampedEm = Math.max(0.01f, em);
		return sized(BOLD, ROOT_SIZE_PX * clampedEm * (float) Math.max(0.01d, scale));
	}

	public static Font bold(double scale)
	{
		return sized(BOLD, ROOT_SIZE_PX * (float) Math.max(0.01d, scale));
	}

	public static Font fullArtTitle(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * TITLE_EM * (float) Math.max(0.01d, scale));
	}

	public static Font fullArtExamine(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * FULL_EXAMINE_EM * (float) Math.max(0.01d, scale));
	}

	public static Font fullArtScore(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * FULL_SCORE_EM * (float) Math.max(0.01d, scale));
	}

	private static Font sized(Font base, float sizePx)
	{
		float size = Math.max(1f, sizePx);
		return base.deriveFont(size);
	}

	private static Font sizedFullArt(Font base, float sizePx)
	{
		float size = Math.max(1f, Math.round(sizePx));
		return base.deriveFont(size);
	}

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

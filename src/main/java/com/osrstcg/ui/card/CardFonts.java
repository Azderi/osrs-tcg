package com.osrstcg.ui.card;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bundled RuneScape fonts for card faces (pack overlay and related UI).
 * Card root matches the website ({@code 15.625px} at scale 1). Banded examine uses
 * {@code runescape.ttf}; title / tier / score / foil-bleed text use {@code runescape_bold.ttf}.
 */
public final class CardFonts
{
	private static final Logger log = LoggerFactory.getLogger(CardFonts.class);

	/** Root size of {@code .album-card} at design scale 1 ({@code 15.625px} on the website). */
	public static final float ROOT_SIZE_PX = 15.625f;
	/** {@code .album-card__title} / {@code .album-card__full-name} is {@code 1.12em}. */
	public static final float TITLE_EM = 1.12f;
	/** {@code .album-card__title.is-multiline} is {@code 0.98em}. */
	public static final float TITLE_MULTILINE_EM = 0.98f;
	/** {@code .album-card__full-examine} is {@code 0.92em}. */
	public static final float FULL_EXAMINE_EM = 0.92f;
	/** {@code .album-card__full-score} / tier is {@code 1.08em}. */
	public static final float FULL_SCORE_EM = 1.08f;

	private static final Font REGULAR = load("com/osrstcg/fonts/runescape.ttf", Font.PLAIN);
	private static final Font BOLD = load("com/osrstcg/fonts/runescape_bold.ttf", Font.BOLD);

	private CardFonts()
	{
	}

	/** Art-fallback / generic body - Regular at {@code root * scale}. */
	public static Font body(double scale)
	{
		return sized(REGULAR, ROOT_SIZE_PX * (float) Math.max(0.01d, scale));
	}

	/**
	 * Banded examine at {@code em} of card root (Regular). Typical fit range
	 * {@link ExamineTextLayout#EXAMINE_EM_MIN}…{@link ExamineTextLayout#EXAMINE_EM_MAX}.
	 */
	public static Font examine(double scale, float em)
	{
		float clampedEm = Math.max(0.01f, em);
		return sized(REGULAR, ROOT_SIZE_PX * clampedEm * (float) Math.max(0.01d, scale));
	}

	/** Card title - Bold at {@code root * 1.12 * scale}. */
	public static Font title(double scale)
	{
		return title(scale, TITLE_EM);
	}

	/** Card title at a custom em of card root (used to fit long names on one line). */
	public static Font title(double scale, float em)
	{
		float clampedEm = Math.max(0.01f, em);
		return sized(BOLD, ROOT_SIZE_PX * clampedEm * (float) Math.max(0.01d, scale));
	}

	/** Two-line card title - Bold at {@code root * 0.98 * scale}. */
	public static Font titleMultiline(double scale)
	{
		return sized(BOLD, ROOT_SIZE_PX * TITLE_MULTILINE_EM * (float) Math.max(0.01d, scale));
	}

	/** Heavier label (rarity tier, score, card-back wordmark). */
	public static Font bold(double scale)
	{
		return sized(BOLD, ROOT_SIZE_PX * (float) Math.max(0.01d, scale));
	}

	/** Foil-bleed title - Bold at {@code root * 1.12 * scale}. */
	public static Font fullArtTitle(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * TITLE_EM * (float) Math.max(0.01d, scale));
	}

	/** Foil-bleed examine - Bold at {@code root * 0.92 * scale}. */
	public static Font fullArtExamine(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * FULL_EXAMINE_EM * (float) Math.max(0.01d, scale));
	}

	/** Foil-bleed score - Bold at {@code root * 1.08 * scale}. */
	public static Font fullArtScore(double scale)
	{
		return sizedFullArt(BOLD, ROOT_SIZE_PX * FULL_SCORE_EM * (float) Math.max(0.01d, scale));
	}

	private static Font sized(Font base, float sizePx)
	{
		float size = Math.max(1f, sizePx);
		return base.deriveFont(size);
	}

	/** Foil-bleed overlays only: round to whole pixels. */
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

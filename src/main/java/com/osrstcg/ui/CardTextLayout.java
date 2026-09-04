package com.osrstcg.ui;

import com.osrstcg.ui.card.CardFonts;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
/**
 * Text layout helpers shared by {@link SharedCardRenderer}: word wrapping, ellipsizing, and
 * centered/wrapped drawing, plus fixed-size full-art width math independent of the actual render scale.
 */
final class CardTextLayout
{
	private static final int FULL_ART_DESIGN_W = 180;
	private static final int FULL_ART_DESIGN_H = 260;
	private static final int FULL_ART_EXAMINE_MAX = 5;
/** Not instantiated; all members are static. */
	private CardTextLayout()
	{
	}
/** Ellipsizes a full-art title to fit the fixed-design title width, falling back to "Unknown Card" if blank. */
	static String ellipsizeFullArtTitle(FontMetrics fm, String text)
	{
		return ellipsizeToWidth(valueOrFallback(text, "Unknown Card"), fm, fullArtDesignTitleMaxWidth());
	}
/**
	 * Wraps full-art examine text to the fixed-design examine width, capping at
	 * {@link #FULL_ART_EXAMINE_MAX} lines and ellipsizing the last line if more text remains.
	 */
	static List<String> wrapFullArtExamine(FontMetrics fm, String text)
	{
		String raw = text == null ? "" : text.trim();
		if (raw.isEmpty())
		{
			return List.of();
		}
		int maxWidth = fullArtDesignExamineMaxWidth();
		List<String> lines = wrapLines(fm, raw, maxWidth);
		if (lines.size() > FULL_ART_EXAMINE_MAX)
		{
			lines = new ArrayList<>(lines.subList(0, FULL_ART_EXAMINE_MAX));
			lines.set(FULL_ART_EXAMINE_MAX - 1,
				ellipsizeToWidth(lines.get(FULL_ART_EXAMINE_MAX - 1), fm, maxWidth));
		}
		return lines;
	}
/** Max pixel width for full-art title text at the fixed design size ({@link #FULL_ART_DESIGN_W}x{@link #FULL_ART_DESIGN_H}, scale 1.0). */
	static int fullArtDesignTitleMaxWidth()
	{
		int innerW = fullArtDesignInnerWidth();
		int titlePadX = Math.max(1, (int) Math.round(6.0d));
		return Math.max(8, innerW - titlePadX * 2);
	}
/** Max pixel width for full-art examine text at the fixed design size. */
	private static int fullArtDesignExamineMaxWidth()
	{
		int innerW = fullArtDesignInnerWidth();
		int examineW = Math.max(8, innerW - Math.max(1, (int) Math.round(12.0d)));
		int examinePadX = Math.max(1, (int) Math.round(6.0d));
		return Math.max(8, examineW - examinePadX * 2);
	}
/** Inner well width at the fixed design size, mirroring {@code SharedCardRenderer.Geometry}'s rim math at scale 1.0. */
	private static int fullArtDesignInnerWidth()
	{
		int rim = Math.max(1, Math.min(Math.min(FULL_ART_DESIGN_W, FULL_ART_DESIGN_H) / 4,
			(int) Math.round(7.0d)));
		return Math.max(1, FULL_ART_DESIGN_W - rim * 2);
	}
/** Draws single-line text centered in {@code rect}, ellipsized to fit and clipped to the rect. */
	static void drawCenteredText(Graphics2D g2, Rectangle rect, String text, Font font, Color color, int horizontalPadding)
	{
		g2.setFont(font == null ? CardFonts.body(1.0d) : font);
		g2.setColor(color == null ? Color.WHITE : color);
		FontMetrics fm = g2.getFontMetrics();
		int pad = Math.max(0, horizontalPadding);
		int maxWidth = Math.max(1, rect.width - pad * 2);
		String value = ellipsizeToWidth(valueOrFallback(text, ""), fm, maxWidth);
		int x = rect.x + pad + Math.max(0, (maxWidth - fm.stringWidth(value)) / 2);
		int y = rect.y + ((rect.height - fm.getHeight()) / 2) + fm.getAscent();
		Shape clip = g2.getClip();
		try
		{
			g2.clip(rect);
			g2.drawString(value, x, y);
		}
		finally
		{
			g2.setClip(clip);
		}
	}
/**
	 * Word-wraps text to fit {@code rect}'s width, truncates to {@code maxLines} (dropping overflow
	 * rather than merging or splitting words), then draws it centered or top-aligned, clipped to the rect.
	 */
	static void drawWrappedCentered(Graphics2D g2, Rectangle rect, String text, Font font, Color color, int maxLines,
		int horizontalPadding, boolean topAlign)
	{
		g2.setFont(font == null ? CardFonts.body(1.0d) : font);
		g2.setColor(color == null ? Color.WHITE : color);
		FontMetrics fm = g2.getFontMetrics();

		int pad = Math.max(0, horizontalPadding);
		int maxWidth = Math.max(8, rect.width - pad * 2);
		int linesCap = Math.max(1, maxLines);
		List<String> lines = wrapLines(fm, valueOrFallback(text, ""), maxWidth);

		// Drop wrapped overflow; never merge leftover words onto the last line or split a word.
		if (lines.size() > linesCap)
		{
			lines = new ArrayList<>(lines.subList(0, linesCap));
		}

		int lineHeight = fm.getHeight();
		int y = topAlign
			? rect.y + fm.getAscent()
			: rect.y + (rect.height - lineHeight * lines.size()) / 2 + fm.getAscent();

		Shape clip = g2.getClip();
		try
		{
			g2.clip(rect);
			for (String line : lines)
			{
				int x = rect.x + pad + Math.max(0, (maxWidth - fm.stringWidth(line)) / 2);
				g2.drawString(line, x, y);
				y += lineHeight;
			}
		}
		finally
		{
			g2.setClip(clip);
		}
	}
/**
	 * Greedy word-wraps text (normalizing line endings, splitting on paragraphs) to fit within
	 * {@code maxWidth}; never breaks a word mid-way. Always returns at least one (possibly empty) line.
	 */
	static List<String> wrapLines(FontMetrics fm, String text, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		String raw = valueOrFallback(text, "").replace("\r\n", "\n").replace('\r', '\n');
		if (raw.trim().isEmpty())
		{
			lines.add("");
			return lines;
		}
		int width = Math.max(1, maxWidth);
		for (String paragraph : raw.split("\n", -1))
		{
			if (paragraph.isEmpty())
			{
				lines.add("");
				continue;
			}
			StringBuilder current = new StringBuilder();
			for (String rawWord : paragraph.split("\\s+"))
			{
				String word = rawWord == null ? "" : rawWord.trim();
				if (word.isEmpty())
				{
					continue;
				}
				String candidate = current.length() == 0 ? word : current + " " + word;
				if (fm.stringWidth(candidate) <= width)
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
		}
		if (lines.isEmpty())
		{
			lines.add("");
		}
		return lines;
	}
/**
	 * Truncates text character by character and appends "..." so the result fits within
	 * {@code maxWidth}; returns "" if even the ellipsis doesn't fit, or {@code text} unchanged if it
	 * already fits.
	 */
	static String ellipsizeToWidth(String text, FontMetrics fm, int maxWidth)
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
/** Returns {@code value} trimmed, or {@code fallback} if {@code value} is null/blank. */
	static String valueOrFallback(String value, String fallback)
	{
		return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
	}
}

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

/** Wrap / ellipsize / centered text helpers for card faces. */
final class CardTextLayout
{
	private static final int FULL_ART_DESIGN_W = 180;
	private static final int FULL_ART_DESIGN_H = 260;
	private static final int FULL_ART_EXAMINE_MAX_LINES = 5;

	private CardTextLayout()
	{
	}

	/** Single-line full-bleed title at design scale (stable across pack-reveal zoom). */
	static String ellipsizeFullArtTitle(FontMetrics fm, String text)
	{
		return ellipsizeToWidth(valueOrFallback(text, "Unknown Card"), fm, fullArtDesignTitleMaxWidth());
	}

	/**
	 * Full-bleed examine lines at design scale so line breaks stay stable when the pack reveal
	 * overlay zooms (font sizes round to whole pixels independently of layout widths).
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
		if (lines.size() > FULL_ART_EXAMINE_MAX_LINES)
		{
			lines = new ArrayList<>(lines.subList(0, FULL_ART_EXAMINE_MAX_LINES));
			lines.set(FULL_ART_EXAMINE_MAX_LINES - 1,
				ellipsizeToWidth(lines.get(FULL_ART_EXAMINE_MAX_LINES - 1), fm, maxWidth));
		}
		return lines;
	}

	static int fullArtDesignTitleMaxWidth()
	{
		int innerW = fullArtDesignInnerWidth();
		int titlePadX = Math.max(1, (int) Math.round(6.0d));
		return Math.max(8, innerW - titlePadX * 2);
	}

	private static int fullArtDesignExamineMaxWidth()
	{
		int innerW = fullArtDesignInnerWidth();
		int examineW = Math.max(8, innerW - Math.max(1, (int) Math.round(12.0d)));
		int examinePadX = Math.max(1, (int) Math.round(6.0d));
		return Math.max(8, examineW - examinePadX * 2);
	}

	private static int fullArtDesignInnerWidth()
	{
		int rim = Math.max(1, Math.min(Math.min(FULL_ART_DESIGN_W, FULL_ART_DESIGN_H) / 4,
			(int) Math.round(7.0d)));
		return Math.max(1, FULL_ART_DESIGN_W - rim * 2);
	}

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

	static String valueOrFallback(String value, String fallback)
	{
		return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
	}
}

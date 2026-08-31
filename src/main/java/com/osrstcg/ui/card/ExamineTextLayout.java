package com.osrstcg.ui.card;

import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

public final class ExamineTextLayout
{
	public static final float EXAMINE_EM_MAX = 1.18f;
	public static final float EXAMINE_EM_MIN = 0.72f;
	public static final float EXAMINE_LINE_HEIGHT = 1.05f;
	public static final int EXAMINE_FIT_STEPS = 12;

	private ExamineTextLayout()
	{
	}

	public static int lineHeightPx(float fontSizePx)
	{
		return Math.max(1, Math.round(fontSizePx * EXAMINE_LINE_HEIGHT));
	}

	public static List<String> wrapBreakWord(FontMetrics fm, String text, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		String raw = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
		if (raw.isEmpty())
		{
			lines.add("");
			return lines;
		}
		int width = Math.max(1, maxWidth);
		String[] paragraphs = raw.split("\n", -1);
		for (String paragraph : paragraphs)
		{
			if (paragraph.isEmpty())
			{
				lines.add("");
				continue;
			}
			wrapParagraphBreakWord(fm, paragraph, width, lines);
		}
		if (lines.isEmpty())
		{
			lines.add("");
		}
		return lines;
	}

	private static void wrapParagraphBreakWord(
		FontMetrics fm, String paragraph, int width, List<String> lines)
	{
		StringBuilder current = new StringBuilder();
		for (String token : paragraph.split("\\s+"))
		{
			if (token == null || token.isEmpty())
			{
				continue;
			}
			if (current.length() == 0)
			{
				if (fm.stringWidth(token) <= width)
				{
					current.append(token);
				}
				else
				{
					breakTokenAcrossLines(fm, token, width, lines, current);
				}
			}
			else if (fm.stringWidth(current + " " + token) <= width)
			{
				current.append(' ').append(token);
			}
			else
			{
				lines.add(current.toString());
				current.setLength(0);
				if (fm.stringWidth(token) <= width)
				{
					current.append(token);
				}
				else
				{
					breakTokenAcrossLines(fm, token, width, lines, current);
				}
			}
		}
		if (current.length() > 0)
		{
			lines.add(current.toString());
		}
	}

	public static float fitExamineEm(FontMetricsFactory metrics, double scale, String text, int maxWidth, int bandHeight)
	{
		String examine = text == null || text.trim().isEmpty() ? "No examine text." : text.trim();
		int width = Math.max(1, maxWidth);
		int height = Math.max(1, bandHeight);

		if (fits(metrics, scale, examine, width, height, EXAMINE_EM_MAX))
		{
			return EXAMINE_EM_MAX;
		}

		float best = EXAMINE_EM_MIN;
		float lo = EXAMINE_EM_MIN;
		float hi = EXAMINE_EM_MAX;
		for (int i = 0; i < EXAMINE_FIT_STEPS; i++)
		{
			float mid = (lo + hi) * 0.5f;
			if (fits(metrics, scale, examine, width, height, mid))
			{
				best = mid;
				lo = mid;
			}
			else
			{
				hi = mid;
			}
		}
		return Math.round(best * 1000f) / 1000f;
	}

	public static boolean fits(
		FontMetricsFactory metrics, double scale, String text, int maxWidth, int bandHeight, float em)
	{
		Font font = CardFonts.examine(scale, em);
		FontMetrics fm = metrics.metricsFor(font);
		List<String> lines = wrapBreakWord(fm, text, maxWidth);
		int lineH = lineHeightPx(font.getSize2D());
		return lines.size() * lineH <= bandHeight + 0.5f;
	}

	@FunctionalInterface
	public interface FontMetricsFactory
	{
		FontMetrics metricsFor(Font font);
	}

	private static void breakTokenAcrossLines(
		FontMetrics fm, String token, int maxWidth, List<String> lines, StringBuilder current)
	{
		int i = 0;
		int len = token.length();
		while (i < len)
		{
			int take = 0;
			while (i + take < len)
			{
				int next = i + take + 1;
				if (Character.isHighSurrogate(token.charAt(i + take)) && next < len
					&& Character.isLowSurrogate(token.charAt(next)))
				{
					next++;
				}
				String candidate = token.substring(i, next);
				if (fm.stringWidth(candidate) <= maxWidth)
				{
					take = next - i;
				}
				else
				{
					break;
				}
			}
			if (take <= 0)
			{
				int next = i + 1;
				if (Character.isHighSurrogate(token.charAt(i)) && next < len
					&& Character.isLowSurrogate(token.charAt(next)))
				{
					next++;
				}
				take = next - i;
			}
			String chunk = token.substring(i, i + take);
			i += take;
			if (i >= len)
			{
				current.append(chunk);
			}
			else
			{
				lines.add(chunk);
			}
		}
	}
}

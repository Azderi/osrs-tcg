package com.osrstcg.ui.tip;

import com.osrstcg.ui.tip.CardInfoTipModel.Content;
import com.osrstcg.ui.tip.CardInfoTipModel.Row;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.FontManager;

public final class CardInfoTipPainter
{
	private static final Color BG = new Color(18, 18, 18, 245);
	private static final Color BORDER = new Color(255, 255, 255, 36);
	private static final Color LABEL = new Color(0xAA, 0xAA, 0xAA);
	private static final Color VALUE = Color.WHITE;
	private static final Color ACTION_HOVER = new Color(255, 255, 255, 28);
	private static final Color SEPARATOR = new Color(255, 255, 255, 36);
	private static final int PAD_X = 13;
	private static final int PAD_Y = 12;
	private static final int RADIUS = 4;
	private static final int TITLE_GAP = 10;
	private static final int ROW_GAP = 7;
	private static final int COL_GAP = 22;
	private static final int ACTION_PAD_Y = 6;
	private static final int MAX_TITLE_CHARS_FALLBACK = 48;

	private CardInfoTipPainter()
	{
	}

	public static Dimension measure(Graphics2D g, Content content)
	{
		Font titleFont = tipTitleFont();
		Font rowFont = tipRowFont();
		FontMetrics titleFm = g.getFontMetrics(titleFont);
		FontMetrics rowFm = g.getFontMetrics(rowFont);

		String title = content == null ? "Card" : content.getTitle();
		List<Row> rows = content == null ? List.of() : content.getRows();

		int titleW = titleFm.stringWidth(ellipsize(title, titleFm, 360));
		int labelW = 0;
		int valueW = 0;
		int actionW = 0;
		boolean hasAction = false;
		boolean hasDetail = false;
		for (Row row : rows)
		{
			if (row.isAction())
			{
				hasAction = true;
				actionW = Math.max(actionW, rowFm.stringWidth(row.getLabel()));
			}
			else
			{
				hasDetail = true;
				labelW = Math.max(labelW, rowFm.stringWidth(row.getLabel()));
				valueW = Math.max(valueW, rowFm.stringWidth(row.getValue()));
			}
		}
		int rowsW = 0;
		if (hasDetail)
		{
			rowsW = Math.max(rowsW, labelW + COL_GAP + valueW);
		}
		if (hasAction)
		{
			rowsW = Math.max(rowsW, actionW);
		}
		int innerW = Math.max(titleW, rowsW);
		int width = PAD_X * 2 + Math.max(1, innerW);

		int height = PAD_Y + titleFm.getHeight();
		if (!rows.isEmpty())
		{
			height += TITLE_GAP;
			boolean sawAction = false;
			for (int i = 0; i < rows.size(); i++)
			{
				Row row = rows.get(i);
				if (row.isAction())
				{
					if (!sawAction && hasDetail)
					{
						height += ROW_GAP + 1;
					}
					sawAction = true;
					height += rowFm.getHeight() + ACTION_PAD_Y * 2;
				}
				else
				{
					height += rowFm.getHeight();
				}
				if (i + 1 < rows.size())
				{
					Row next = rows.get(i + 1);
					if (!row.isAction() && !next.isAction())
					{
						height += ROW_GAP;
					}
				}
			}
			if (!rows.get(rows.size() - 1).isAction())
			{
				height += PAD_Y;
			}
		}
		else
		{
			height += PAD_Y;
		}
		return new Dimension(width, height);
	}

	public static void paint(Graphics2D g, int x, int y, Content content, Color titleColor, float alpha, float yOffset,
		Integer hoverX, Integer hoverY, Map<String, Rectangle> outActionBounds)
	{
		if (content == null || alpha <= 0.01f)
		{
			return;
		}
		if (outActionBounds != null)
		{
			outActionBounds.clear();
		}
		Dimension size = measure(g, content);
		int drawY = y + Math.round(yOffset);

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
			g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.min(1f, Math.max(0f, alpha))));

			RoundRectangle2D panel = new RoundRectangle2D.Float(x, drawY, size.width, size.height, RADIUS * 2f, RADIUS * 2f);
			g2.setColor(BG);
			g2.fill(panel);
			g2.setColor(BORDER);
			g2.draw(panel);

			Font titleFont = tipTitleFont();
			Font rowFont = tipRowFont();
			FontMetrics titleFm = g2.getFontMetrics(titleFont);
			FontMetrics rowFm = g2.getFontMetrics(rowFont);

			int textX = x + PAD_X;
			int cursorY = drawY + PAD_Y + titleFm.getAscent();
			g2.setFont(titleFont);
			g2.setColor(titleColor == null ? Color.WHITE : titleColor);
			int maxTitleW = size.width - PAD_X * 2;
			g2.drawString(ellipsize(content.getTitle(), titleFm, maxTitleW), textX, cursorY);

			List<Row> rows = content.getRows();
			if (rows.isEmpty())
			{
				return;
			}

			cursorY += titleFm.getDescent() + TITLE_GAP;
			g2.setFont(rowFont);
			int valueRight = x + size.width - PAD_X;
			boolean sawDetail = false;
			boolean drewSeparator = false;
			for (int i = 0; i < rows.size(); i++)
			{
				Row row = rows.get(i);
				if (row.isAction())
				{
					if (sawDetail && !drewSeparator)
					{
						cursorY += ROW_GAP;
						int sepY = cursorY;
						g2.setColor(SEPARATOR);
						g2.drawLine(x + 1, sepY, x + size.width - 2, sepY);
						cursorY += 1;
						drewSeparator = true;
					}
					int rowTop = cursorY;
					int rowH = rowFm.getHeight() + ACTION_PAD_Y * 2;
					Rectangle hit = new Rectangle(x + 1, rowTop, size.width - 2, rowH);
					boolean hover = hoverX != null && hoverY != null && hit.contains(hoverX, hoverY);
					if (hover)
					{
						g2.setColor(ACTION_HOVER);
						g2.fillRect(hit.x, hit.y, hit.width, hit.height);
					}
					g2.setColor(VALUE);
					g2.drawString(row.getLabel(), textX, rowTop + ACTION_PAD_Y + rowFm.getAscent());
					if (outActionBounds != null && row.getActionId() != null)
					{
						outActionBounds.put(row.getActionId(), hit);
					}
					cursorY = rowTop + rowH;
				}
				else
				{
					sawDetail = true;
					cursorY += rowFm.getAscent();
					g2.setColor(LABEL);
					g2.drawString(row.getLabel(), textX, cursorY);
					Color valueColor = row.getValueColor() == null ? VALUE : row.getValueColor();
					g2.setColor(valueColor);
					int vw = rowFm.stringWidth(row.getValue());
					g2.drawString(row.getValue(), valueRight - vw, cursorY);
					cursorY += rowFm.getDescent();
					if (i + 1 < rows.size() && !rows.get(i + 1).isAction())
					{
						cursorY += ROW_GAP;
					}
				}
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	public static void paint(Graphics2D g, int x, int y, Content content, Color titleColor, float alpha, float yOffset)
	{
		paint(g, x, y, content, titleColor, alpha, yOffset, null, null, null);
	}

	private static Font tipTitleFont()
	{
		return FontManager.getRunescapeBoldFont();
	}

	private static Font tipRowFont()
	{
		return FontManager.getRunescapeSmallFont();
	}

	private static String ellipsize(String text, FontMetrics fm, int maxWidth)
	{
		String value = text == null ? "" : text;
		if (fm.stringWidth(value) <= maxWidth)
		{
			return value;
		}
		String ellipsis = "…";
		int ellipsisW = fm.stringWidth(ellipsis);
		if (ellipsisW >= maxWidth)
		{
			return ellipsis;
		}
		int lo = 0;
		int hi = Math.min(value.length(), MAX_TITLE_CHARS_FALLBACK);
		while (lo < hi)
		{
			int mid = (lo + hi + 1) / 2;
			if (fm.stringWidth(value.substring(0, mid)) + ellipsisW <= maxWidth)
			{
				lo = mid;
			}
			else
			{
				hi = mid - 1;
			}
		}
		return value.substring(0, lo) + ellipsis;
	}
}

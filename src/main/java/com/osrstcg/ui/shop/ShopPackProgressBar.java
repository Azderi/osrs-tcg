package com.osrstcg.ui.shop;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;

/**
 * Fixed-size dual-segment progress bar for a shop tile: a green fill for standard-owned cards overlaid
 * with a yellow fill for foil-owned cards, both as a percentage of the set total. Immutable once
 * constructed; must be built and painted on the EDT.
 */
public final class ShopPackProgressBar extends JPanel
{
	public static final int WIDTH_PX = 75;
	public static final int HEIGHT_PX = 6;

	private static final Color TRACK = new Color(0x2e2e2e);
	private static final Color TRACK_BORDER = new Color(0x555555);
	private static final Color STANDARD_FILL = new Color(0x4caf50);
	private static final Color FOIL_FILL = new Color(0xF2C94C);

	private final int barWidthPx;
	private final int standardFillPx;
	private final int foilFillPx;

	/** Precomputes fill widths (clamped to 0-100%) from the owned/total counts for painting. */
	public ShopPackProgressBar(int barWidthPx, int standardOwn, int foilOwn, int total)
	{
		this.barWidthPx = barWidthPx;
		double standardPct = total <= 0 ? 0.0 : (100.0 * standardOwn) / total;
		double foilPct = total <= 0 ? 0.0 : (100.0 * foilOwn) / total;
		this.standardFillPx = (int) Math.round(barWidthPx * Math.min(100.0, Math.max(0.0, standardPct)) / 100.0);
		this.foilFillPx = (int) Math.round(barWidthPx * Math.min(100.0, Math.max(0.0, foilPct)) / 100.0);
		int outerW = barWidthPx + 2;
		int outerH = HEIGHT_PX + 2;
		Dimension size = new Dimension(outerW, outerH);
		setOpaque(false);
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);
		setAlignmentX(Component.CENTER_ALIGNMENT);
	}

	/** Paints the bordered track, then the standard and foil fill segments over it. */
	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			int x = 1;
			int y = 1;
			int h = HEIGHT_PX;
			g2.setColor(TRACK_BORDER);
			g2.fillRect(0, 0, barWidthPx + 2, h + 2);
			g2.setColor(TRACK);
			g2.fillRect(x, y, barWidthPx, h);
			if (standardFillPx > 0)
			{
				g2.setColor(STANDARD_FILL);
				g2.fillRect(x, y, standardFillPx, h);
			}
			if (foilFillPx > 0)
			{
				g2.setColor(FOIL_FILL);
				g2.fillRect(x, y, foilFillPx, h);
			}
		}
		finally
		{
			g2.dispose();
		}
	}
}

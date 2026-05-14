package com.runelitetcg.ui.collectionalbum;

import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.service.WikiImageCacheService;
import com.runelitetcg.ui.SharedCardRenderer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

final class CollectionAlbumGridPanel extends JPanel
{
	private static final int COLS = 7;
	private static final int ROWS = 3;
	private static final int GAP = 5;
	/** Vertical space below each card face for the owned-count line (Runescape small + padding). */
	private static final int QTY_LABEL_RESERVE_PX = 18;

	private final WikiImageCacheService imageCacheService;
	private final BiConsumer<Integer, AlbumSlot> ownedMultiCopyPressed;
	private final Runnable onSelectionChanged;
	private List<AlbumSlot> slots = Collections.emptyList();
	private List<Rectangle> lastCardBounds = Collections.emptyList();
	private int selectedIndex = -1;

	CollectionAlbumGridPanel(WikiImageCacheService imageCacheService,
		BiConsumer<Integer, AlbumSlot> ownedMultiCopyPressed,
		Runnable onSelectionChanged)
	{
		this.imageCacheService = imageCacheService;
		this.ownedMultiCopyPressed = ownedMultiCopyPressed;
		this.onSelectionChanged = onSelectionChanged == null ? () -> {} : onSelectionChanged;
		setOpaque(true);
		setBackground(new Color(0x1E1E1E));
		setMinimumSize(new Dimension(400, 260));
		setPreferredSize(new Dimension(720, 420));
		setToolTipText("");

		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				handlePress(e);
			}
		});
	}

	void clearSelection()
	{
		selectedIndex = -1;
		repaint();
	}

	int getSelectedIndex()
	{
		return selectedIndex;
	}

	AlbumSlot getSelectedSlot()
	{
		if (selectedIndex < 0 || selectedIndex >= slots.size())
		{
			return null;
		}
		return slots.get(selectedIndex);
	}

	private void handlePress(MouseEvent e)
	{
		if (e == null)
		{
			return;
		}
		int next = -1;
		for (int i = 0; i < lastCardBounds.size(); i++)
		{
			Rectangle r = lastCardBounds.get(i);
			if (r != null && r.contains(e.getPoint()))
			{
				next = i;
				break;
			}
		}
		if (next < 0 || next >= slots.size() || !slots.get(next).ownedAny())
		{
			return;
		}
		AlbumSlot slot = slots.get(next);
		if (slot.totalOwnedQty() > 1)
		{
			selectedIndex = -1;
			repaint();
			onSelectionChanged.run();
			if (ownedMultiCopyPressed != null)
			{
				ownedMultiCopyPressed.accept(next, slot);
			}
			return;
		}
		if (next == selectedIndex)
		{
			selectedIndex = -1;
		}
		else
		{
			selectedIndex = next;
		}
		repaint();
		onSelectionChanged.run();
	}

	void setSlots(List<AlbumSlot> next)
	{
		this.slots = next == null ? Collections.emptyList() : new ArrayList<>(next);
		selectedIndex = -1;
		repaint();
		onSelectionChanged.run();
	}

	@Override
	public String getToolTipText(MouseEvent event)
	{
		if (event == null)
		{
			return null;
		}
		for (int i = 0; i < lastCardBounds.size() && i < slots.size(); i++)
		{
			Rectangle r = lastCardBounds.get(i);
			if (r != null && r.contains(event.getPoint()))
			{
				AlbumSlot slot = slots.get(i);
				if (slot == null || !slot.ownedAny())
				{
					return null;
				}
				String tip = slot.singleCopyHoverTooltip();
				return tip == null || tip.isEmpty() ? null : tip;
			}
		}
		return null;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		List<Rectangle> paintedBounds = new ArrayList<>();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			int w = getWidth();
			int h = getHeight();
			if (w <= 0 || h <= 0)
			{
				lastCardBounds = Collections.emptyList();
				return;
			}

			Insets ins = getInsets();
			int innerW = Math.max(0, w - ins.left - ins.right);
			int innerH = Math.max(0, h - ins.top - ins.bottom);
			if (innerW <= 0 || innerH <= 0)
			{
				lastCardBounds = Collections.emptyList();
				return;
			}

			if (slots.isEmpty())
			{
				g2.setColor(new Color(0xAAAAAA));
				g2.drawString("No cards match the current filters.", ins.left + 16, ins.top + 24);
				lastCardBounds = Collections.emptyList();
				return;
			}

			int cellW = (innerW - (COLS - 1) * GAP) / COLS;
			int cellH = (innerH - (ROWS - 1) * GAP) / ROWS;
			int contentH = Math.max(1, cellH - QTY_LABEL_RESERVE_PX);
			double scale = Math.min(
				cellW / (double) SharedCardRenderer.DEFAULT_CARD_WIDTH,
				contentH / (double) SharedCardRenderer.DEFAULT_CARD_HEIGHT) * 0.94d;
			int cW = Math.max(1, (int) Math.round(SharedCardRenderer.DEFAULT_CARD_WIDTH * scale));
			int cH = Math.max(1, (int) Math.round(SharedCardRenderer.DEFAULT_CARD_HEIGHT * scale));

			for (int i = 0; i < slots.size() && i < COLS * ROWS; i++)
			{
				int col = i % COLS;
				int row = i / COLS;
				int cx = col * (cellW + GAP);
				int cy = row * (cellH + GAP);
				int ox = cx + (cellW - cW) / 2;
				int oy = cy + (contentH - cH) / 2;
				Rectangle bounds = new Rectangle(ins.left + ox, ins.top + oy, cW, cH);
				paintedBounds.add(bounds);

				AlbumSlot slot = slots.get(i);
				CardDefinition card = slot.card();
				Color rarity = slot.rarityColor();
				BufferedImage art = imageCacheService.getCached(card == null ? null : card.getImageUrl());
				if (!slot.ownedAny())
				{
					g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
				}
				boolean foilScoreLabel = slot.ownedAny() && slot.displayFoil();
				SharedCardRenderer.drawCardFace(g2, bounds, card, slot.displayFoil(), rarity, art, 0L, foilScoreLabel);
				if (!slot.ownedAny())
				{
					g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
				}

				String qtyLine = qtyLabel(slot);
				if (!qtyLine.isEmpty())
				{
					g2.setColor(new Color(0xDDDDDD));
					g2.setFont(FontManager.getRunescapeSmallFont());
					int tw = g2.getFontMetrics().stringWidth(qtyLine);
					int tx = ins.left + ox + (cW - tw) / 2;
					int ty = ins.top + oy + cH + g2.getFontMetrics().getAscent() + 2;
					g2.drawString(qtyLine, tx, ty);
				}

				if (slot.ownedAny() && selectedIndex == i)
				{
					g2.setColor(new Color(0x00E5FF));
					g2.setStroke(new BasicStroke(2f));
					g2.drawRoundRect(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2, 8, 8);
				}
			}
		}
		finally
		{
			g2.dispose();
			lastCardBounds = paintedBounds;
		}
	}

	private static String qtyLabel(AlbumSlot s)
	{
		if (s == null || !s.ownedAny())
		{
			return "";
		}
		int t = s.totalOwnedQty();
		if (t <= 1)
		{
			return "";
		}
		int nf = s.nonFoilQty();
		int ff = s.foilQty();
		if (nf > 0 && ff > 0)
		{
			return nf + "x normal, " + ff + "x foil";
		}
		if (nf > 0)
		{
			return nf + "x normal";
		}
		if (ff > 0)
		{
			return ff + "x foil";
		}
		return "";
	}
}

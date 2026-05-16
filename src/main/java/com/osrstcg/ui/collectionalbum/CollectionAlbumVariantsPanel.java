package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDefinition;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.SharedCardRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Same card-face grid style as the main album ({@link CollectionAlbumGridPanel}), 7×3 with paging past 21 copies.
 * Paging controls live in the host window; call {@link #setPagingControls(JButton, JButton, JLabel)} once.
 */
public final class CollectionAlbumVariantsPanel extends JPanel
{
	private static final int COLS = 7;
	private static final int ROWS = 3;
	private static final int PAGE_SIZE = COLS * ROWS;
	private static final int GAP = 5;
	private static final int QTY_LABEL_RESERVE_PX = 18;

	private final WikiImageCacheService imageCacheService;
	private final Consumer<OwnedCardInstance> onPick;
	private final VariantGrid variantGrid;

	private JButton pagingPrevBtn;
	private JButton pagingNextBtn;
	private JLabel pagingPageLbl;
	private boolean pagingListenersAttached;

	private CardDefinition card;
	private Color rarityColor = Color.GRAY;
	private List<OwnedCardInstance> allCopies = List.of();
	private int variantPageIndex;
	private String selectedInstanceId;

	public CollectionAlbumVariantsPanel(WikiImageCacheService imageCacheService, Consumer<OwnedCardInstance> onPick)
	{
		super(new BorderLayout());
		this.imageCacheService = imageCacheService;
		this.onPick = onPick;
		this.variantGrid = new VariantGrid();
		setOpaque(true);
		setBackground(new Color(0x1E1E1E));
		add(variantGrid, BorderLayout.CENTER);
	}

	/**
	 * Wire album-window paging widgets; safe to call once from the window constructor.
	 */
	public void setPagingControls(JButton prev, JButton next, JLabel pageLabel)
	{
		this.pagingPrevBtn = prev;
		this.pagingNextBtn = next;
		this.pagingPageLbl = pageLabel;
		if (!pagingListenersAttached && prev != null && next != null)
		{
			prev.addActionListener(e -> shiftVariantPage(-1));
			next.addActionListener(e -> shiftVariantPage(1));
			pagingListenersAttached = true;
		}
	}

	public void setVariants(CardDefinition card, Color rarity, List<OwnedCardInstance> copies, String initialSelectionId)
	{
		this.card = card;
		this.rarityColor = rarity == null ? Color.GRAY : rarity;
		selectedInstanceId = initialSelectionId;
		variantPageIndex = 0;
		allCopies = new ArrayList<>();
		if (copies != null)
		{
			for (OwnedCardInstance c : copies)
			{
				if (c != null)
				{
					allCopies.add(c);
				}
			}
		}
		if (initialSelectionId != null && !initialSelectionId.isEmpty())
		{
			for (int i = 0; i < allCopies.size(); i++)
			{
				if (initialSelectionId.equals(allCopies.get(i).getInstanceId()))
				{
					variantPageIndex = i / PAGE_SIZE;
					break;
				}
			}
		}
		updatePagingUi();
		variantGrid.repaint();
		revalidate();
	}

	/** True when the current variants page shows at least one foil copy (moving sheen). */
	public boolean needsFoilAnimationRepaint()
	{
		int from = variantPageIndex * PAGE_SIZE;
		for (int i = 0; i < PAGE_SIZE; i++)
		{
			int gi = from + i;
			if (gi >= allCopies.size())
			{
				break;
			}
			OwnedCardInstance inst = allCopies.get(gi);
			if (inst != null && inst.isFoil())
			{
				return true;
			}
		}
		return false;
	}

	public void shiftVariantPage(int delta)
	{
		int nPages = Math.max(1, (allCopies.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		variantPageIndex = Math.max(0, Math.min(nPages - 1, variantPageIndex + delta));
		updatePagingUi();
		variantGrid.repaint();
	}

	private void updatePagingUi()
	{
		boolean paging = allCopies.size() > PAGE_SIZE;
		if (pagingPrevBtn != null)
		{
			pagingPrevBtn.setVisible(paging);
		}
		if (pagingNextBtn != null)
		{
			pagingNextBtn.setVisible(paging);
		}
		if (pagingPageLbl != null)
		{
			pagingPageLbl.setVisible(paging);
		}
		if (!paging || pagingPrevBtn == null || pagingNextBtn == null || pagingPageLbl == null)
		{
			return;
		}
		int nPages = Math.max(1, (allCopies.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		pagingPageLbl.setText(String.format(Locale.US, "Page %d / %d", variantPageIndex + 1, nPages));
		pagingPrevBtn.setEnabled(variantPageIndex > 0);
		pagingNextBtn.setEnabled(variantPageIndex < nPages - 1);
	}

	private void selectInstance(OwnedCardInstance inst)
	{
		if (inst == null)
		{
			return;
		}
		selectedInstanceId = inst.getInstanceId();
		variantGrid.repaint();
		if (onPick != null)
		{
			onPick.accept(inst);
		}
	}

	private final class VariantGrid extends JPanel
	{
		private List<Rectangle> lastCardBounds = Collections.emptyList();

		VariantGrid()
		{
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

		private void handlePress(MouseEvent e)
		{
			if (e == null)
			{
				return;
			}
			int from = variantPageIndex * PAGE_SIZE;
			for (int i = 0; i < lastCardBounds.size(); i++)
			{
				Rectangle r = lastCardBounds.get(i);
				if (r != null && r.contains(e.getPoint()))
				{
					int gi = from + i;
					if (gi >= 0 && gi < allCopies.size())
					{
						selectInstance(allCopies.get(gi));
					}
					return;
				}
			}
		}

		@Override
		public String getToolTipText(MouseEvent event)
		{
			if (event == null)
			{
				return null;
			}
			int from = variantPageIndex * PAGE_SIZE;
			for (int i = 0; i < lastCardBounds.size(); i++)
			{
				Rectangle r = lastCardBounds.get(i);
				if (r != null && r.contains(event.getPoint()))
				{
					int gi = from + i;
					if (gi >= 0 && gi < allCopies.size())
					{
						return AlbumInstanceTooltip.format(allCopies.get(gi));
					}
					return null;
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

				if (allCopies.isEmpty())
				{
					g2.setColor(new Color(0xAAAAAA));
					g2.drawString("No copies.", ins.left + 16, ins.top + 24);
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

				int from = variantPageIndex * PAGE_SIZE;
				for (int i = 0; i < PAGE_SIZE; i++)
				{
					int col = i % COLS;
					int row = i / COLS;
					int cx = col * (cellW + GAP);
					int cy = row * (cellH + GAP);
					int ox = cx + (cellW - cW) / 2;
					int oy = cy + (contentH - cH) / 2;
					Rectangle bounds = new Rectangle(ins.left + ox, ins.top + oy, cW, cH);
					paintedBounds.add(bounds);

					int gi = from + i;
					if (gi >= allCopies.size())
					{
						continue;
					}
					OwnedCardInstance inst = allCopies.get(gi);
					BufferedImage art = imageCacheService.getCached(card == null ? null : card.getImageUrl());
					boolean foil = inst.isFoil();
					SharedCardRenderer.drawCardFace(g2, bounds, card, foil, rarityColor, art, 0L, foil);

					if (selectedInstanceId != null && selectedInstanceId.equals(inst.getInstanceId()))
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
	}
}

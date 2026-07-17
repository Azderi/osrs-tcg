package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.service.RarityMath;
import com.osrstcg.service.TcgTradeListShareService;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.SharedCardRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

final class PartyTradeMatchesPanel extends JPanel
{
	private final MatchGridPanel grid;
	private final JLabel statusLabel = new JLabel(" ");
	private final Timer imagePollTimer;

	PartyTradeMatchesPanel(CardDatabase cardDatabase, WikiImageCacheService imageCacheService,
		List<TcgTradeListShareService.TradeMatch> matches, String displayName)
	{
		super(new BorderLayout(0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setPreferredSize(new Dimension(760, 560));

		JLabel title = new JLabel(displayName == null ? "Party trade matches" : displayName);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		north.setOpaque(false);
		north.add(title);
		add(north, BorderLayout.NORTH);

		grid = new MatchGridPanel(cardDatabase, imageCacheService, matches);
		JScrollPane scrollPane = new JScrollPane(grid);
		scrollPane.getViewport().setBackground(new Color(0x1E1E1E));
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setPreferredSize(MatchGridPanel.viewportSizeForRows(2));
		add(scrollPane, BorderLayout.CENTER);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setText(statusText(matches));
		JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
		south.setOpaque(true);
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.add(statusLabel);
		add(south, BorderLayout.SOUTH);

		imagePollTimer = new Timer(250, null);
		imagePollTimer.addActionListener(e ->
		{
			if (grid.needsImageLoadRepaint())
			{
				grid.repaint();
			}
			else
			{
				imagePollTimer.stop();
			}
		});
		if (grid.needsImageLoadRepaint())
		{
			imagePollTimer.start();
		}
	}

	void stopTimers()
	{
		imagePollTimer.stop();
	}

	private static String statusText(List<TcgTradeListShareService.TradeMatch> matches)
	{
		if (matches == null || matches.isEmpty())
		{
			return "No shared duplicates from this player are missing from your collection.";
		}
		boolean incompatible = matches.stream().anyMatch(m -> !m.isTransferCompatible());
		return incompatible
			? "These matches were shared, but rates/debug do not match for direct sending."
			: "Showing shared duplicate variants missing from your collection.";
	}

	private static final class MatchGridPanel extends JPanel
	{
		private static final int COLS = 4;
		private static final int GAP = 12;
		private static final int CARD_W = 126;
		private static final int CARD_H = 182;
		private static final int LABEL_H = 28;

		private final CardDatabase cardDatabase;
		private final WikiImageCacheService imageCacheService;
		private final List<TcgTradeListShareService.TradeMatch> matches;
		private final AlbumRarityTable rarityTable;

		private MatchGridPanel(CardDatabase cardDatabase, WikiImageCacheService imageCacheService,
			List<TcgTradeListShareService.TradeMatch> matches)
		{
			this.cardDatabase = cardDatabase;
			this.imageCacheService = imageCacheService;
			this.matches = matches == null ? List.of() : List.copyOf(matches);
			this.rarityTable = AlbumRarityTable.build(cardDatabase.getCards());
			setBackground(new Color(0x1E1E1E));
			setOpaque(true);
			setPreferredSize(preferredGridSize(this.matches.size()));
			preloadVisibleArtwork();
		}

		boolean needsImageLoadRepaint()
		{
			for (TcgTradeListShareService.TradeMatch match : matches)
			{
				CardDefinition card = cardForName(match.getCardName());
				String url = card == null ? null : card.getImageUrl();
				if (url != null && !url.trim().isEmpty() && imageCacheService.needsLoad(url))
				{
					return true;
				}
			}
			return false;
		}

		private void preloadVisibleArtwork()
		{
			List<String> urls = new ArrayList<>();
			for (TcgTradeListShareService.TradeMatch match : matches)
			{
				CardDefinition card = cardForName(match.getCardName());
				if (card != null && card.getImageUrl() != null && !card.getImageUrl().trim().isEmpty())
				{
					urls.add(card.getImageUrl());
				}
			}
			imageCacheService.preload(urls);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				Rectangle visible = getVisibleRect();
				Shape oldClip = g2.getClip();
				g2.setClip(visible);
				g2.setColor(getBackground());
				g2.fillRect(visible.x, visible.y, visible.width, visible.height);
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

				if (matches.isEmpty())
				{
					g2.setFont(FontManager.getRunescapeSmallFont());
					g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
					g2.drawString("No matches for the selected party member.", 16, 28);
				}
				else
				{
					for (int i = 0; i < matches.size(); i++)
					{
						TcgTradeListShareService.TradeMatch match = matches.get(i);
						int col = i % COLS;
						int row = i / COLS;
						int x = GAP + col * (CARD_W + GAP);
						int y = GAP + row * (CARD_H + LABEL_H + GAP);
						Rectangle slotBounds = new Rectangle(x, y, CARD_W, CARD_H + LABEL_H);
						if (!slotBounds.intersects(visible))
						{
							continue;
						}

						Rectangle cardBounds = new Rectangle(x, y, CARD_W, CARD_H);
						CardDefinition card = cardForName(match.getCardName());
						Color rarity = rarityTable.colorForCardName(match.getCardName());
						BufferedImage art = imageCacheService.getCached(card == null ? null : card.getImageUrl());
						SharedCardRenderer.drawCardFace(g2, cardBounds, card, match.isFoil(), rarity, art, 0L, match.isFoil());

						g2.setFont(FontManager.getRunescapeSmallFont());
						g2.setColor(match.isTransferCompatible() ? Color.WHITE : new Color(0xE6, 0xA8, 0x4A));
						String label = label(match);
						int tx = x + Math.max(0, (CARD_W - g2.getFontMetrics().stringWidth(label)) / 2);
						g2.drawString(label, tx, y + CARD_H + 18);
					}
				}
				g2.setClip(oldClip);
			}
			finally
			{
				g2.dispose();
			}
		}

		private CardDefinition cardForName(String name)
		{
			if (name == null || name.trim().isEmpty())
			{
				return null;
			}
			return cardDatabase.findByName(name).orElse(null);
		}

		private static String label(TcgTradeListShareService.TradeMatch match)
		{
			return String.format("%d available%s", match.getAvailable(), match.isFoil() ? " foil" : "");
		}

		private static Dimension preferredGridSize(int count)
		{
			if (count <= 0)
			{
				return viewportSizeForRows(2);
			}
			int rows = (int) Math.ceil(count / (double) COLS);
			int width = GAP + COLS * (CARD_W + GAP);
			int height = GAP + rows * (CARD_H + LABEL_H + GAP);
			return new Dimension(width, Math.max(viewportSizeForRows(2).height, height));
		}

		private static Dimension viewportSizeForRows(int rows)
		{
			int safeRows = Math.max(1, rows);
			int width = GAP + COLS * (CARD_W + GAP) + 20;
			int height = GAP + safeRows * (CARD_H + LABEL_H + GAP);
			return new Dimension(width, height);
		}
	}
}

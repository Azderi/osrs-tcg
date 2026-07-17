package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.service.RarityMath;
import com.osrstcg.service.TcgTradeListShareService;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.SharedCardRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

final class PartyTradeMatchesPanel extends JPanel
{
	private static final String RARITY_FILTER_ALL = "All";
	private static final List<String> RARITY_TIERS_LOW_TO_HIGH = List.of(
		"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic", "Godly");

	private final CardDatabase cardDatabase;
	private final AlbumRarityTable rarityTable;
	private final List<TcgTradeListShareService.TradeMatch> allMatches;
	private final MatchGridPanel grid;
	private final JLabel statusLabel = new JLabel(" ");
	private final JTextField searchField = new JTextField(16);
	private final JComboBox<AlbumSortMode> sortCombo = new JComboBox<>(AlbumSortMode.values());
	private final JComboBox<String> rarityCombo = new JComboBox<>();
	private final JCheckBox foilOnlyCheck = new JCheckBox("Foil only");
	private final Timer imagePollTimer;

	PartyTradeMatchesPanel(CardDatabase cardDatabase, WikiImageCacheService imageCacheService,
		List<TcgTradeListShareService.TradeMatch> matches, String displayName)
	{
		super(new BorderLayout(0, 8));
		this.cardDatabase = cardDatabase;
		this.rarityTable = AlbumRarityTable.build(cardDatabase.getCards());
		this.allMatches = matches == null ? List.of() : List.copyOf(matches);

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setPreferredSize(new Dimension(760, 560));

		JLabel title = new JLabel(displayName == null ? "Party trade matches" : displayName);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		titleRow.setOpaque(false);
		titleRow.add(title);

		JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
		filterRow.setOpaque(false);
		JLabel searchLbl = new JLabel("Search:");
		searchLbl.setForeground(Color.WHITE);
		filterRow.add(searchLbl);
		filterRow.add(searchField);
		JLabel sortLbl = new JLabel("Sort:");
		sortLbl.setForeground(Color.WHITE);
		filterRow.add(sortLbl);
		filterRow.add(sortCombo);
		JLabel rarityLbl = new JLabel("Rarity:");
		rarityLbl.setForeground(Color.WHITE);
		filterRow.add(rarityLbl);
		filterRow.add(rarityCombo);
		filterRow.add(foilOnlyCheck);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setOpaque(false);
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		filterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(titleRow);
		north.add(filterRow);
		add(north, BorderLayout.NORTH);

		grid = new MatchGridPanel(cardDatabase, imageCacheService, List.of());
		JScrollPane scrollPane = new JScrollPane(grid);
		scrollPane.getViewport().setBackground(new Color(0x1E1E1E));
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setPreferredSize(MatchGridPanel.viewportSizeForRows(2));
		add(scrollPane, BorderLayout.CENTER);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
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
		setupFilters();
		rebuildModel();
		if (grid.needsImageLoadRepaint())
		{
			imagePollTimer.start();
		}
	}

	void stopTimers()
	{
		imagePollTimer.stop();
	}

	private void setupFilters()
	{
		searchField.setForeground(Color.WHITE);
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuildModel();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuildModel();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuildModel();
			}
		});

		sortCombo.setForeground(Color.WHITE);
		sortCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sortCombo.addActionListener(e -> rebuildModel());

		rarityCombo.addItem(RARITY_FILTER_ALL);
		for (String tier : RARITY_TIERS_LOW_TO_HIGH)
		{
			rarityCombo.addItem(tier);
		}
		rarityCombo.setSelectedIndex(0);
		rarityCombo.setForeground(Color.WHITE);
		rarityCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rarityCombo.addActionListener(e -> rebuildModel());

		foilOnlyCheck.setForeground(Color.WHITE);
		foilOnlyCheck.setOpaque(false);
		foilOnlyCheck.addActionListener(e -> rebuildModel());
	}

	private void rebuildModel()
	{
		List<TcgTradeListShareService.TradeMatch> working = allMatches.stream()
			.filter(m -> m != null && m.getCardName() != null && !m.getCardName().trim().isEmpty())
			.collect(Collectors.toCollection(ArrayList::new));

		String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
		if (!q.isEmpty())
		{
			working.removeIf(m -> !m.getCardName().toLowerCase(Locale.ROOT).contains(q));
		}

		String rarityPick = (String) rarityCombo.getSelectedItem();
		if (rarityPick != null && !RARITY_FILTER_ALL.equals(rarityPick))
		{
			working.removeIf(m ->
			{
				CardDefinition card = cardForName(m.getCardName());
				return card == null || !rarityPick.equals(rarityTable.tierLabelForCard(card));
			});
		}

		if (foilOnlyCheck.isSelected())
		{
			working.removeIf(m -> !m.isFoil());
		}

		sortMatches(working);
		grid.setMatches(working);
		statusLabel.setText(statusText(allMatches, working));
		if (grid.needsImageLoadRepaint())
		{
			imagePollTimer.restart();
		}
	}

	private void sortMatches(List<TcgTradeListShareService.TradeMatch> matches)
	{
		AlbumSortMode mode = (AlbumSortMode) sortCombo.getSelectedItem();
		if (mode == null)
		{
			mode = AlbumSortMode.SCORE_DESC;
		}
		Comparator<TcgTradeListShareService.TradeMatch> byName = Comparator.comparing(
			m -> m.getCardName() == null ? "" : m.getCardName(),
			String.CASE_INSENSITIVE_ORDER);
		switch (mode)
		{
			case SCORE_DESC:
				matches.sort(Comparator.<TcgTradeListShareService.TradeMatch>comparingDouble(this::matchScore)
					.reversed()
					.thenComparing(byName)
					.thenComparing(TcgTradeListShareService.TradeMatch::isFoil));
				break;
			case RARITY_DESC:
				matches.sort(Comparator.<TcgTradeListShareService.TradeMatch>comparingInt(
					m -> tierSortKey(rarityTable.tierLabelForCard(cardForName(m.getCardName()))))
					.reversed()
					.thenComparing(byName)
					.thenComparing(TcgTradeListShareService.TradeMatch::isFoil));
				break;
			case NAME_ASC:
			default:
				matches.sort(byName.thenComparing(TcgTradeListShareService.TradeMatch::isFoil));
				break;
		}
	}

	private double matchScore(TcgTradeListShareService.TradeMatch match)
	{
		CardDefinition card = cardForName(match.getCardName());
		if (card == null)
		{
			return 0.0d;
		}
		return match.isFoil() ? RarityMath.foilAdjustedScoreRounded(card) : RarityMath.score(card);
	}

	private CardDefinition cardForName(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return null;
		}
		return cardDatabase.findByName(name).orElse(null);
	}

	private static String statusText(List<TcgTradeListShareService.TradeMatch> allMatches,
		List<TcgTradeListShareService.TradeMatch> filteredMatches)
	{
		if (allMatches == null || allMatches.isEmpty())
		{
			return "No shared duplicates from this player are missing from your collection.";
		}
		if (filteredMatches == null || filteredMatches.isEmpty())
		{
			return "No matches for the current filters.";
		}
		boolean incompatible = filteredMatches.stream().anyMatch(m -> !m.isTransferCompatible());
		String count = filteredMatches.size() == allMatches.size()
			? String.format(Locale.US, "Showing %d shared duplicate variant%s.",
				filteredMatches.size(), filteredMatches.size() == 1 ? "" : "s")
			: String.format(Locale.US, "Showing %d of %d shared duplicate variants.",
				filteredMatches.size(), allMatches.size());
		return incompatible
			? "These matches were shared, but rates/debug do not match for direct sending."
			: count;
	}

	private static int tierSortKey(String label)
	{
		if (label == null)
		{
			return 0;
		}
		switch (label)
		{
			case "Common":
				return 0;
			case "Uncommon":
				return 1;
			case "Rare":
				return 2;
			case "Epic":
				return 3;
			case "Legendary":
				return 4;
			case "Mythic":
				return 5;
			case "Godly":
				return 6;
			default:
				return 0;
		}
	}

	private static final class MatchGridPanel extends JPanel
	{
		private static final int COLS = 5;
		private static final int GAP = 12;
		private static final int CARD_W = 126;
		private static final int CARD_H = 182;
		private static final int LABEL_H = 28;

		private final CardDatabase cardDatabase;
		private final WikiImageCacheService imageCacheService;
		private List<TcgTradeListShareService.TradeMatch> matches;
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

		private void setMatches(List<TcgTradeListShareService.TradeMatch> matches)
		{
			this.matches = matches == null ? List.of() : List.copyOf(matches);
			setPreferredSize(preferredGridSize(this.matches.size()));
			preloadVisibleArtwork();
			revalidate();
			repaint();
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

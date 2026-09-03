package com.osrstcg.ui.overview;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.layout.PackCloseSnapshot;
import com.osrstcg.ui.layout.SidebarLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.net.URL;
import java.util.List;
import java.util.function.IntSupplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Renders the sidebar's Overview tab: a credits row plus a two-column grid of collection stat tiles
 * (unique/foil counts, completion percentages, opened packs, collection score), optionally annotated
 * with hiscores ranks. Swing component; {@link #render} and {@link #updateCredits} must run on the EDT.
 */
public final class OverviewTab
{
	private static final int STAT_GRID_GAP = 6;

	private final OsrsTcgConfig config;
	private final TcgStateService stateService;
	private final IntSupplier contentWidth;
	private final Class<?> imageResourceClass;
	private JLabel creditsValueLabel;

	/** @param imageResourceClass class whose classloader resolves stat icon resource paths */
	public OverviewTab(
		OsrsTcgConfig config,
		TcgStateService stateService,
		IntSupplier contentWidth,
		Class<?> imageResourceClass)
	{
		this.config = config;
		this.stateService = stateService;
		this.contentWidth = contentWidth;
		this.imageResourceClass = imageResourceClass;
	}

	/** Builds and adds the credits panel and the stat tile grid to {@code target}, using {@code snap}/{@code m} for values and optional hiscores ranks. */
	public void render(JPanel target, PackCloseSnapshot snap, CloudSidebarCollectionStats m)
	{
		int[] ranks = config.showSidebarRanks() ? stateService.getSidebarRanks() : null;
		Integer totalCardsRank = rankAt(ranks, 4, m.getTotalCardsOwned() > 0);
		Integer foilCardsRank = rankAt(ranks, 5, m.getFoilOwned() > 0L);
		Integer completionRank = rankAt(ranks, 0, m.getCompletionPct() > 0.0d);
		Integer foilCompletionRank = rankAt(ranks, 1, m.getFoilCompletionPct() > 0.0d);
		Integer openedPacksRank = rankAt(ranks, 2, snap.openedPacks > 0L);
		Integer collectionScoreRank = rankAt(ranks, 3, m.getCollectionScore() > 0L);
		boolean reserveRankRow = totalCardsRank != null || foilCardsRank != null || completionRank != null
			|| foilCompletionRank != null || openedPacksRank != null || collectionScoreRank != null;
		JPanel creditsPanel = imageStatPanel("Credits", SidebarLayout.format(snap.credits), SidebarLayout.CREDITS_IMAGE_PATH);
		Component east = ((BorderLayout) creditsPanel.getLayout()).getLayoutComponent(BorderLayout.EAST);
		creditsValueLabel = east instanceof JLabel ? (JLabel) east : null;
		target.add(creditsPanel);
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(twoColumnGridPanel(List.of(
			statBoxPanel("Unique cards", SidebarLayout.format(m.getUniqueOwned()) + " / " + SidebarLayout.format(m.getTotalCardPool())),
			statBoxPanel("Unique foil cards", SidebarLayout.format(m.getUniqueFoilOwned()) + " / " + SidebarLayout.format(m.getTotalCardPool())),
			statBoxPanel("Total cards", SidebarLayout.format(m.getTotalCardsOwned()), totalCardsRank, reserveRankRow),
			statBoxPanel("Foil cards", SidebarLayout.format(m.getFoilOwned()), foilCardsRank, reserveRankRow),
			statBoxPanel("Collection %", String.format("%.2f%%", m.getCompletionPct()),
				completionRank, reserveRankRow),
			statBoxPanel("Collection Foil %", String.format("%.2f%%", m.getFoilCompletionPct()),
				foilCompletionRank, reserveRankRow),
			statBoxPanel("Opened packs", SidebarLayout.format(snap.openedPacks), openedPacksRank, reserveRankRow),
			statBoxPanel("Collection score", SidebarLayout.format(m.getCollectionScore()), collectionScoreRank, reserveRankRow)
		), STAT_GRID_GAP));
	}

	/** Updates the already-rendered credits value label in place, if the tab has been rendered. */
	public void updateCredits(long credits)
	{
		if (creditsValueLabel != null)
		{
			creditsValueLabel.setText(SidebarLayout.format(credits));
		}
	}

	/** Builds a full-width row panel: an icon (if the resource resolves) and label on the left, value on the right. */
	public JPanel imageStatPanel(String labelText, String valueText, String imagePath)
	{
		JPanel panel = new JPanel(new BorderLayout(8, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(4, 6, 4, 6));

		JPanel left = new JPanel(new BorderLayout(6, 0));
		left.setOpaque(false);

		URL imgUrl = imageResourceClass.getResource(imagePath);
		if (imgUrl != null)
		{
			ImageIcon icon = new ImageIcon(imgUrl);
			JLabel iconLabel = new JLabel(icon);
			iconLabel.setHorizontalAlignment(SwingConstants.LEFT);
			left.add(iconLabel, BorderLayout.WEST);
		}

		JLabel label = SidebarLayout.textPanel(SidebarLayout.shorten(labelText, 24));
		label.setToolTipText(labelText);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		left.add(label, BorderLayout.CENTER);

		JLabel value = SidebarLayout.textPanel(valueText);
		value.setHorizontalAlignment(SwingConstants.RIGHT);

		panel.add(left, BorderLayout.CENTER);
		panel.add(value, BorderLayout.EAST);
		SidebarLayout.clampPanelWidth(panel);
		return panel;
	}

	/** @return the rank at {@code index}, or {@code null} if not shown, the array is missing, the index is out of range, or the rank is non-positive. */
	private static Integer rankAt(int[] ranks, int index, boolean show)
	{
		if (!show || ranks == null || index < 0 || index >= ranks.length)
		{
			return null;
		}
		int rank = ranks[index];
		return rank > 0 ? rank : null;
	}

	/** @return {@code "#<rank>"}, or {@code "#<n>k"} for ranks of 1000 or more. */
	static String formatHiscoresRank(int rank)
	{
		if (rank < 1000)
		{
			return "#" + rank;
		}
		return "#" + (rank / 1000) + "k";
	}

	/** @return a tiered color for a hiscores rank (gold/pink/red/purple/blue/green/white by threshold), dimmed once rank exceeds 1000. */
	static Color colorHiscoresRank(int rank)
	{
		Color base;
		if (rank <= 200)
		{
			base = new Color(0xF2C94C);
		}
		else if (rank <= 500)
		{
			base = new Color(0xFF6EC7);
		}
		else if (rank <= 1000)
		{
			base = new Color(0xE74C3C);
		}
		else if (rank <= 5000)
		{
			base = new Color(0x9B59B6);
		}
		else if (rank <= 12000)
		{
			base = new Color(0x3498DB);
		}
		else if (rank <= 20000)
		{
			base = new Color(0x2ECC71);
		}
		else
		{
			base = Color.WHITE;
		}
		return rank > 1000 ? dimHiscoresRankColor(base) : base;
	}

	/** Scales down a color's RGB channels to indicate a lower-priority hiscores rank. */
	private static Color dimHiscoresRankColor(Color color)
	{
		final float factor = 0.62f;
		return new Color(
			Math.round(color.getRed() * factor),
			Math.round(color.getGreen() * factor),
			Math.round(color.getBlue() * factor));
	}

	/** {@link #statBoxPanel(String, String, Integer, boolean)} with no rank row. */
	private JPanel statBoxPanel(String labelText, String valueText)
	{
		return statBoxPanel(labelText, valueText, null, false);
	}

	/**
	 * Builds a single stat tile: centered label, bold value, and an optional rank line beneath. When
	 * {@code hiscoresRank} is null but {@code reserveRankRow} is true, a blank rank line is still
	 * reserved so tiles in the same row stay the same height.
	 */
	private JPanel statBoxPanel(String labelText, String valueText, Integer hiscoresRank, boolean reserveRankRow)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(8, 6, 8, 6));

		JLabel label = SidebarLayout.textPanel(SidebarLayout.shorten(labelText, 24));
		label.setToolTipText(labelText);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel value = SidebarLayout.textPanel(valueText);
		value.setHorizontalAlignment(SwingConstants.CENTER);
		value.setAlignmentX(Component.CENTER_ALIGNMENT);
		value.setFont(FontManager.getRunescapeBoldFont());

		panel.add(label);
		panel.add(Box.createRigidArea(new Dimension(0, 4)));
		panel.add(value);
		if (hiscoresRank != null || reserveRankRow)
		{
			JLabel rank = SidebarLayout.textPanel(hiscoresRank != null ? formatHiscoresRank(hiscoresRank) : " ");
			rank.setFont(FontManager.getRunescapeSmallFont());
			if (hiscoresRank != null)
			{
				rank.setForeground(colorHiscoresRank(hiscoresRank));
				rank.setToolTipText("Hiscores rank");
			}
			rank.setHorizontalAlignment(SwingConstants.CENTER);
			rank.setAlignmentX(Component.CENTER_ALIGNMENT);
			panel.add(Box.createRigidArea(new Dimension(0, 5)));
			panel.add(rank);
		}

		int w = overviewStatBoxWidth();
		Dimension pref = panel.getPreferredSize();
		Dimension sized = new Dimension(w, pref.height);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setPreferredSize(sized);
		panel.setMaximumSize(sized);
		panel.setMinimumSize(new Dimension(0, pref.height));
		return panel;
	}

	/** @return the width of one stat tile in a two-column grid, given the available content width. */
	private int overviewStatBoxWidth()
	{
		int inner = contentWidth.getAsInt();
		return Math.max(96, (inner - STAT_GRID_GAP) / 2);
	}

	/** Lays out {@code tiles} two-per-row, centered horizontally, with {@code gap} pixels between tiles and rows. */
	private JPanel twoColumnGridPanel(List<JPanel> tiles, int gap)
	{
		JPanel grid = new JPanel();
		grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);

		for (int i = 0; i < tiles.size(); i += 2)
		{
			if (i > 0)
			{
				grid.add(Box.createVerticalStrut(gap));
			}
			JPanel row = new JPanel();
			row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
			row.setOpaque(false);
			row.setAlignmentX(Component.CENTER_ALIGNMENT);
			row.add(Box.createHorizontalGlue());
			row.add(tiles.get(i));
			if (i + 1 < tiles.size())
			{
				row.add(Box.createHorizontalStrut(gap));
				row.add(tiles.get(i + 1));
			}
			row.add(Box.createHorizontalGlue());
			Dimension rowPref = row.getPreferredSize();
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowPref.height));
			grid.add(row);
		}

		Dimension gridPref = grid.getPreferredSize();
		grid.setPreferredSize(gridPref);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, gridPref.height));
		return grid;
	}
}

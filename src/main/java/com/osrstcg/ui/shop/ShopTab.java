package com.osrstcg.ui.shop;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.pack.PackOpenCoordinator;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.layout.PackCloseSnapshot;
import com.osrstcg.ui.layout.SidebarLayout;
import com.osrstcg.ui.overview.OverviewTab;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

public final class ShopTab
{
	private static final int BOOSTER_GRID_GAP = 6;

	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;
	private final PackRevealService packRevealService;
	private final PackOpenCoordinator packOpenCoordinator;
	private final PackCatalogService packCatalogService;
	private final CardImageCacheService imageCacheService;
	private final OsrsTcgConfig config;
	private final CloudSessionService cloudSessionService;
	private final OverviewTab overviewTab;
	private final IntSupplier shopWidth;
	private final Supplier<PackCloseSnapshot> snapshotSupplier;
	private final Runnable refreshUi;
	private final Runnable beginRevealFreeze;
	private final Runnable clearRevealFreeze;

	private final JPanel shopHeaderPanel;
	private final JPanel packsContent;
	private final AtomicBoolean packOpenInFlight = new AtomicBoolean(false);
	private JLabel creditsValueLabel;
	private final List<JButton> buyButtons = new ArrayList<>();
	private final List<Integer> buyPrices = new ArrayList<>();

	public ShopTab(
		TcgStateService stateService,
		CardDatabase cardDatabase,
		PackRevealService packRevealService,
		PackOpenCoordinator packOpenCoordinator,
		PackCatalogService packCatalogService,
		CardImageCacheService imageCacheService,
		OsrsTcgConfig config,
		CloudSessionService cloudSessionService,
		OverviewTab overviewTab,
		IntSupplier shopWidth,
		Supplier<PackCloseSnapshot> snapshotSupplier,
		Runnable refreshUi,
		Runnable beginRevealFreeze,
		Runnable clearRevealFreeze,
		JPanel shopHeaderPanel,
		JPanel packsContent)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.packRevealService = packRevealService;
		this.packOpenCoordinator = packOpenCoordinator;
		this.packCatalogService = packCatalogService;
		this.imageCacheService = imageCacheService;
		this.config = config;
		this.cloudSessionService = cloudSessionService;
		this.overviewTab = overviewTab;
		this.shopWidth = shopWidth;
		this.snapshotSupplier = snapshotSupplier;
		this.refreshUi = refreshUi;
		this.beginRevealFreeze = beginRevealFreeze;
		this.clearRevealFreeze = clearRevealFreeze;
		this.shopHeaderPanel = shopHeaderPanel;
		this.packsContent = packsContent;
	}

	public void clear()
	{
		shopHeaderPanel.removeAll();
		packsContent.removeAll();
		buyButtons.clear();
		buyPrices.clear();
		creditsValueLabel = null;
	}

	public void render()
	{
		PackCloseSnapshot displaySnap = snapshotSupplier.get();
		List<BoosterShopRow> shopRows = ShopProgress.computeRows(
			displaySnap, cardDatabase.getCards(), RollPoolFilter.filterRollPool(cardDatabase.getCards()),
			shopVisibleBoosters());
		renderFromPackClose(displaySnap, shopRows);
	}

	public void renderFromPackClose(PackCloseSnapshot snap, List<BoosterShopRow> shopRows)
	{
		preloadShopPackThumbnails(shopRows);
		rebuildShopHeader(snap.credits);
		buyButtons.clear();
		buyPrices.clear();
		packsContent.removeAll();
		packsContent.add(boosterShopPanelFromPrecalc(snap.credits, shopRows));
		packsContent.revalidate();
		packsContent.repaint();
	}

	/** Update the credits header and buy-button enabled state without rebuilding pack tiles. */
	public void updateCredits(long credits)
	{
		if (creditsValueLabel != null)
		{
			creditsValueLabel.setText(SidebarLayout.format(credits));
		}
		applyBuyButtonEnabledState(credits);
	}

	public List<BoosterShopRow> computeRows(PackCloseSnapshot snap)
	{
		return ShopProgress.computeRows(
			snap, cardDatabase.getCards(), RollPoolFilter.filterRollPool(cardDatabase.getCards()),
			shopVisibleBoosters());
	}

	private void preloadShopPackThumbnails(List<BoosterShopRow> shopRows)
	{
		if (config.compactShop() || shopRows == null || imageCacheService == null)
		{
			return;
		}
		List<String> urls = new ArrayList<>();
		for (BoosterShopRow row : shopRows)
		{
			if (row == null || row.booster == null)
			{
				continue;
			}
			String thumb = row.booster.getThumbnail();
			if (BoosterPackDefinition.isHostedImagePath(thumb))
			{
				urls.add(thumb.trim());
			}
		}
		if (!urls.isEmpty())
		{
			imageCacheService.preload(urls);
		}
	}

	private void rebuildShopHeader(long credits)
	{
		shopHeaderPanel.removeAll();
		JPanel creditsPanel = overviewTab.imageStatPanel("Credits", SidebarLayout.format(credits), SidebarLayout.CREDITS_IMAGE_PATH);
		Component east = ((BorderLayout) creditsPanel.getLayout()).getLayoutComponent(BorderLayout.EAST);
		creditsValueLabel = east instanceof JLabel ? (JLabel) east : null;
		shopHeaderPanel.add(creditsPanel);
		shopHeaderPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		shopHeaderPanel.revalidate();
		shopHeaderPanel.repaint();
	}

	private JPanel boosterShopPanelFromPrecalc(long credits, List<BoosterShopRow> rows)
	{
		JPanel outer = new JPanel();
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setOpaque(false);

		if (rows == null || rows.isEmpty())
		{
			outer.add(infoPanel("No booster packs available."));
			SidebarLayout.clampFixedWidth(outer, shopWidth.getAsInt());
			return outer;
		}

		int buttonW = shopBoosterButtonWidth();

		JPanel grid = new JPanel();
		grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		List<JButton> buttons = new ArrayList<>();
		buyButtons.clear();
		buyPrices.clear();
		for (BoosterShopRow row : rows)
		{
			if (row == null || row.booster == null)
			{
				continue;
			}
			JButton buy = createBoosterBuyButton(
				row.booster, row.progressOwn, row.progressFoilOwn, row.progressTotal, buttonW);
			buyButtons.add(buy);
			buyPrices.add(row.booster.getPrice());
			buttons.add(buy);
		}
		applyBuyButtonEnabledState(credits);

		for (int i = 0; i < buttons.size(); i += 2)
		{
			if (i > 0)
			{
				grid.add(Box.createVerticalStrut(BOOSTER_GRID_GAP));
			}
			JPanel row = new JPanel();
			row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
			row.setOpaque(false);
			row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
			row.add(buttons.get(i));
			if (i + 1 < buttons.size())
			{
				row.add(Box.createHorizontalStrut(BOOSTER_GRID_GAP));
				row.add(buttons.get(i + 1));
			}
			row.add(Box.createHorizontalGlue());
			int inner = shopWidth.getAsInt();
			int rowH = Math.max(1, row.getPreferredSize().height);
			row.setPreferredSize(new Dimension(inner, rowH));
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowH));
			row.setMinimumSize(new Dimension(0, rowH));
			grid.add(row);
		}

		Dimension gridPref = grid.getPreferredSize();
		grid.setPreferredSize(gridPref);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, gridPref.height));

		outer.add(grid);
		SidebarLayout.clampFixedWidth(outer, shopWidth.getAsInt());
		return outer;
	}

	private void applyBuyButtonEnabledState(long credits)
	{
		boolean consentPending = cloudSessionService.needsCloudConsent();
		boolean revealBusy = packRevealService.isActive();
		int n = Math.min(buyButtons.size(), buyPrices.size());
		for (int i = 0; i < n; i++)
		{
			JButton buy = buyButtons.get(i);
			int price = buyPrices.get(i);
			buy.setEnabled(!revealBusy && !consentPending && credits >= price);
			if (consentPending)
			{
				buy.setToolTipText("Create a profile before opening packs.");
			}
			else
			{
				buy.setToolTipText(null);
			}
		}
	}

	private List<BoosterPackDefinition> shopVisibleBoosters()
	{
		return new ArrayList<>(packCatalogService.getVisibleBoosters());
	}

	private int shopBoosterButtonWidth()
	{
		int inner = shopWidth.getAsInt();
		return Math.max(96, (inner - BOOSTER_GRID_GAP) / 2);
	}

	private JPanel infoPanel(String message)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(6, 6, 6, 6));
		JLabel label = SidebarLayout.textPanel(message);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		panel.add(label, BorderLayout.CENTER);
		SidebarLayout.clampPanelWidth(panel);
		return panel;
	}

	private ImageIcon shopPackIcon(BoosterPackDefinition booster)
	{
		String thumbnail = booster == null ? null : booster.getThumbnail();
		if (!BoosterPackDefinition.isHostedImagePath(thumbnail))
		{
			return null;
		}
		java.awt.image.BufferedImage remote = imageCacheService.getCached(thumbnail.trim());
		return remote != null ? new ImageIcon(remote) : null;
	}

	private JButton createBoosterBuyButton(BoosterPackDefinition booster, int progressOwn, int progressFoilOwn, int progressTotal,
		int buttonWidth)
	{
		boolean compact = config.compactShop();
		return BoosterBuyButtonFactory.create(
			booster, progressOwn, progressFoilOwn, progressTotal, buttonWidth,
			compact ? null : shopPackIcon(booster),
			compact,
			() -> packOpenCoordinator.openFromShop(
				booster, packOpenInFlight, beginRevealFreeze, clearRevealFreeze, refreshUi,
				SwingUtilities::invokeLater));
	}
}

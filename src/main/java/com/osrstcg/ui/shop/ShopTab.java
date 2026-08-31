package com.osrstcg.ui.shop;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.catalog.PackImageUrls;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.shop.CloudSellService;
import com.osrstcg.credit.DuplicateSellPlanner;
import com.osrstcg.pack.PackOpenCoordinator;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.layout.PackCloseSnapshot;
import com.osrstcg.ui.layout.SidebarLayout;
import com.osrstcg.ui.overview.OverviewTab;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

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
	private final CloudSellService cloudSellService;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final OverviewTab overviewTab;
	private final IntSupplier shopWidth;
	private final Supplier<PackCloseSnapshot> snapshotSupplier;
	private final Runnable refreshUi;
	private final Runnable beginRevealFreeze;
	private final Runnable clearRevealFreeze;
	private final Component dialogParent;

	private final JPanel shopHeaderPanel;
	private final JPanel packsContent;
	private final JButton sellDuplicatesButton;
	private final AtomicBoolean packOpenInFlight = new AtomicBoolean(false);
	private final AtomicBoolean sellInFlight = new AtomicBoolean(false);
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
		CloudSellService cloudSellService,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		OverviewTab overviewTab,
		IntSupplier shopWidth,
		Supplier<PackCloseSnapshot> snapshotSupplier,
		Runnable refreshUi,
		Runnable beginRevealFreeze,
		Runnable clearRevealFreeze,
		Component dialogParent,
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
		this.cloudSellService = cloudSellService;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.overviewTab = overviewTab;
		this.shopWidth = shopWidth;
		this.snapshotSupplier = snapshotSupplier;
		this.refreshUi = refreshUi;
		this.beginRevealFreeze = beginRevealFreeze;
		this.clearRevealFreeze = clearRevealFreeze;
		this.dialogParent = dialogParent;
		this.shopHeaderPanel = shopHeaderPanel;
		this.packsContent = packsContent;
		this.sellDuplicatesButton = createSellDuplicatesButton();
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
		updateSellDuplicatesButtonState();
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
			if (PackImageUrls.isHostedPath(thumb))
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
		shopHeaderPanel.add(sellDuplicatesPanel());
		updateSellDuplicatesButtonState();
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
			clampShopPanelWidth(outer);
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
		clampShopPanelWidth(outer);
		return outer;
	}

	private void applyBuyButtonEnabledState(long credits)
	{
		boolean needsProfileCreate = cloudSessionService.needsProfileCreate();
		boolean consentPending = needsProfileCreate;
		boolean revealBusy = packRevealService.isActive();
		int n = Math.min(buyButtons.size(), buyPrices.size());
		for (int i = 0; i < n; i++)
		{
			JButton buy = buyButtons.get(i);
			int price = buyPrices.get(i);
			buy.setEnabled(!revealBusy && !consentPending && credits >= price);
			if (needsProfileCreate)
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

	private void clampShopPanelWidth(JPanel panel)
	{
		int w = shopWidth.getAsInt();
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		Dimension preferred = panel.getPreferredSize();
		panel.setPreferredSize(new Dimension(w, preferred.height));
		panel.setMaximumSize(new Dimension(w, preferred.height));
		panel.setMinimumSize(new Dimension(0, preferred.height));
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
		if (!PackImageUrls.isHostedPath(thumbnail))
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

	private JPanel sellDuplicatesPanel()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(6, 6, 6, 6));
		panel.add(sellDuplicatesButton, BorderLayout.CENTER);
		SidebarLayout.clampPanelWidth(panel);
		return panel;
	}

	private void updateSellDuplicatesButtonState()
	{
		List<OwnedCardInstance> instances = stateService.getState().getCollectionState().getOwnedInstances();
		boolean hasDuplicates = DuplicateSellPlanner.hasSellableDuplicates(instances);
		boolean cloudReady = cloudSessionService.isReady();
		boolean busy = sellInFlight.get();
		sellDuplicatesButton.setEnabled(hasDuplicates && cloudReady && !busy);
		if (busy)
		{
			sellDuplicatesButton.setToolTipText("Sell in progress…");
		}
		else if (!cloudReady)
		{
			sellDuplicatesButton.setToolTipText(cloudSessionService.needsProfileCreate()
				? "Create a profile before selling"
				: "Cloud offline - cannot sell");
		}
		else if (!hasDuplicates)
		{
			sellDuplicatesButton.setToolTipText("No sellable duplicates");
		}
		else
		{
			sellDuplicatesButton.setToolTipText(null);
		}
	}

	private void promptAndSellDuplicates()
	{
		if (sellInFlight.get())
		{
			return;
		}
		if (!cloudSessionService.isReady())
		{
			String reason = cloudSessionService.needsProfileCreate()
				? "Create a profile before selling cards."
				: "Cloud offline - cannot sell cards.";
			TcgPluginGameMessages.queueGameMessage(chatMessageManager, "[OSRS TCG] " + reason);
			refreshUi.run();
			return;
		}

		if (!sellInFlight.compareAndSet(false, true))
		{
			return;
		}
		updateSellDuplicatesButtonState();
		scheduler.execute(() ->
		{
			cloudSessionService.forceRefreshCollectionState();
			DuplicateSellPlanner.Result plan;
			synchronized (stateService)
			{
				plan = DuplicateSellPlanner.plan(
					new ArrayList<>(stateService.getState().getCollectionState().getOwnedInstances()),
					this::cardDefinitionForName);
			}
			DuplicateSellPlanner.Result planned = plan;
			SwingUtilities.invokeLater(() ->
			{
				if (planned.getCardsSold() <= 0 || planned.getSoldInstanceIds().isEmpty())
				{
					sellInFlight.set(false);
					updateSellDuplicatesButtonState();
					TcgPluginGameMessages.queueGameMessage(chatMessageManager,
						"[OSRS TCG] No sellable duplicates.");
					refreshUi.run();
					return;
				}

				int choice = JOptionPane.showConfirmDialog(
					dialogParent,
					"Are you sure you want to sell " + planned.getCardsSold()
						+ " cards for " + SidebarLayout.format(planned.getCreditsToAdd()) + " credits?",
					"Sell duplicates",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE);
				if (choice != JOptionPane.YES_OPTION)
				{
					sellInFlight.set(false);
					updateSellDuplicatesButtonState();
					return;
				}

				scheduler.execute(() ->
				{
					var result = cloudSellService.sellDuplicates(planned);
					SwingUtilities.invokeLater(() ->
					{
						try
						{
							if (!cloudSessionService.isAccountLocked()
								&& result.getMessage() != null && !result.getMessage().isEmpty())
							{
								TcgPluginGameMessages.queueGameMessage(chatMessageManager,
									"[OSRS TCG] " + result.getMessage());
							}
							refreshUi.run();
						}
						finally
						{
							sellInFlight.set(false);
							updateSellDuplicatesButtonState();
						}
					});
				});
			});
		});
	}

	private JButton createSellDuplicatesButton()
	{
		JButton button = new JButton("Sell duplicates");
		button.setFocusable(false);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setForeground(Color.WHITE);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(6, 6, 6, 6)
		));
		button.addActionListener(ev -> promptAndSellDuplicates());
		return button;
	}

	private CardDefinition cardDefinitionForName(String cardName)
	{
		return cardDatabase.findByName(cardName).orElse(null);
	}
}

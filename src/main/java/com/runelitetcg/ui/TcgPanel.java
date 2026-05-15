package com.runelitetcg.ui;

import com.runelitetcg.RuneLiteTcgConfig;
import com.runelitetcg.data.BoosterPackDefinition;
import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.data.PackCatalog;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.OwnedCardInstance;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.model.TcgState;
import com.runelitetcg.service.CreditAwardService;
import com.runelitetcg.service.PackOpeningService;
import com.runelitetcg.service.PackRevealService;
import com.runelitetcg.service.RarityMath;
import com.runelitetcg.service.RollPoolFilter;
import com.runelitetcg.service.TcgStateService;
import com.runelitetcg.ui.collectionalbum.CollectionAlbumManager;
import com.runelitetcg.util.NumberFormatting;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Slf4j
@Singleton
public class TcgPanel extends PluginPanel
{
	private static final String REWARD_TUNING_LOCKED_TOOLTIP =
		"Locked while you have credits, opened packs, or collection cards. Use Reset collection below to change these.";

	private static final String REWARD_TUNING_NON_DEFAULT_TRADE_WARNING =
		"Your settings do not match the defaults. You will not be able to trade with other players unless their settings match with you!";

	private static final String TCG_WELCOME_HEADER = "Welcome to OSRS TCG";

	private static final String TCG_WELCOME_BODY =
		"A card collecting game where you earn credits by gaining xp, leveling up and killing things. "
			+ "The plugin features trading cards with other players through party integration. "
			+ "Trading with other players requires matching plugin settings and the multipliers for receiving credits are locked in after you start "
			+ "so it is adviced to keep everything as default and debug mode off. "
			+ "If you want to change these settings later on, you must reset your collection and start from nothing.";

	private static final String TCG_WELCOME_TCG_COMMAND_BODY =
		"Type !tcg in chat to share your collection stats";

	private enum Tab
	{
		WELCOME("Welcome"),
		OVERVIEW("Overview"),
		SHOP("Shop");

		@Getter
		private final String label;

		Tab(String label)
		{
			this.label = label;
		}
	}

	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;
	private final PackOpeningService packOpeningService;
	private final PackRevealService packRevealService;
	private final PackCatalog packCatalog;
	private final RuneLiteTcgConfig config;
	private final Client client;
	private final CollectionAlbumManager collectionAlbumManager;
	private final CreditAwardService creditAwardService;
	private final JButton sellDuplicatesButton;

	private final JPanel mainPanel = new JPanel();
	private final JPanel content = new JPanel();
	private final CardLayout contentLayout = new CardLayout();
	private final JPanel welcomeContent = new JPanel();
	private final JPanel overviewContent = new JPanel();
	private final JPanel packsContent = new JPanel();
	private final JScrollPane shopScrollPane = new JScrollPane(packsContent);
	private final JPanel loggedOutContent = new JPanel();
	private final JPanel footerPanel = new JPanel();
	private final JPanel titlePanel;
	private final JButton welcomeTabButton = new JButton(Tab.WELCOME.getLabel());
	private final JButton overviewTabButton = new JButton(Tab.OVERVIEW.getLabel());
	private final JButton shopTabButton = new JButton(Tab.SHOP.getLabel());
	private final Map<String, Long> scoreByCardName = new HashMap<>();
	private Tab selectedTab = Tab.OVERVIEW;
	/** After first in-world refresh, {@link #selectedTab} is only user-driven unless reset clears progress. */
	private boolean defaultTabSelectionInitialized;
	private boolean pendingRefresh;
	private boolean refreshQueued;
	private volatile boolean panelVisible;
	private int lastPanelWidthForLayout = -1;
	/** Bumps when a new pack-close refresh is scheduled so stale async results are ignored. */
	private final AtomicLong packCloseRefreshGen = new AtomicLong();
	/** While a pack is opening, sidebar stats use this pre-transaction snapshot so pulls are not spoiled. */
	private PackCloseSnapshot sidebarRevealSpoilerFreeze;
	/** During an active reveal, each tab is built at most once from {@link #sidebarRevealSpoilerFreeze}. */
	private boolean welcomeBuiltForActiveReveal;
	private boolean overviewBuiltForActiveReveal;
	private boolean shopBuiltForActiveReveal;

	private int rewardDraftFoil = 1;
	private double rewardDraftKill = 1.0d;
	private double rewardDraftLevel = 1.0d;
	private double rewardDraftXp = 1.0d;

	private final boolean runeliteDeveloperMode;

	@Inject
	public TcgPanel(
		TcgStateService stateService,
		CardDatabase cardDatabase,
		PackOpeningService packOpeningService,
		PackRevealService packRevealService,
		PackCatalog packCatalog,
		RuneLiteTcgConfig config,
		Client client,
		CollectionAlbumManager collectionAlbumManager,
		CreditAwardService creditAwardService,
		@Named("developerMode") boolean runeliteDeveloperMode)
	{
		super(false);
		this.runeliteDeveloperMode = runeliteDeveloperMode;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.packOpeningService = packOpeningService;
		this.packRevealService = packRevealService;
		this.packCatalog = packCatalog;
		this.config = config;
		this.client = client;
		this.collectionAlbumManager = collectionAlbumManager;
		this.creditAwardService = creditAwardService;
		this.sellDuplicatesButton = createSellDuplicatesButton();

		setLayout(new BorderLayout());

		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setLayout(new BorderLayout(0, 8));
		mainPanel.setBorder(new EmptyBorder(6, 6, 6, 6));

		content.setLayout(contentLayout);
		content.setOpaque(false);
		initializeTabContentPanel(welcomeContent);
		initializeTabContentPanel(overviewContent);
		initializeTabContentPanel(packsContent);
		initializeTabContentPanel(loggedOutContent);
		loggedOutContent.add(infoPanel("Please log in."));
		content.add(welcomeContent, Tab.WELCOME.name());
		content.add(overviewContent, Tab.OVERVIEW.name());
		content.add(shopScrollPane, Tab.SHOP.name());
		content.add(loggedOutContent, "LOGGED_OUT");

		shopScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		shopScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
		shopScrollPane.setBorder(BorderFactory.createEmptyBorder());
		shopScrollPane.setOpaque(false);
		shopScrollPane.getViewport().setOpaque(false);
		shopScrollPane.setWheelScrollingEnabled(true);
		shopScrollPane.getVerticalScrollBar().setUnitIncrement(16);

		populateFooterPanel();

		titlePanel = buildTitlePanel();
		mainPanel.add(titlePanel, BorderLayout.NORTH);
		mainPanel.add(content, BorderLayout.CENTER);
		mainPanel.add(footerPanel, BorderLayout.SOUTH);

		add(mainPanel, BorderLayout.CENTER);

		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentShown(ComponentEvent e)
			{
				panelVisible = true;
				refresh();
			}

			@Override
			public void componentHidden(ComponentEvent e)
			{
				panelVisible = false;
			}

			@Override
			public void componentResized(ComponentEvent e)
			{
				if (!panelVisible)
				{
					return;
				}
				int nw = getWidth();
				if (nw > 0 && nw != lastPanelWidthForLayout)
				{
					lastPanelWidthForLayout = nw;
					refresh();
				}
			}
		});

		panelVisible = isShowing();
	}

	public void start()
	{
		cardDatabase.load();
		packCatalog.load();
		if (!runeliteDeveloperMode && stateService.isDebugLogging())
		{
			stateService.setDebugLogging(false);
		}
		rebuildRarityColorMap();
		syncRewardDraftFromPersistent();
		ensureRootAttached();
		refresh();
	}

	/** Persists foil/multiplier draft values before credits increase (see {@link TcgStateService#addCredits(long)}). */
	public void flushRewardTuningDraftToState()
	{
		if (stateService.isRewardTuningLocked())
		{
			return;
		}
		stateService.tryUpdateRewardTuning(
			new RewardTuningState(rewardDraftFoil, rewardDraftKill, rewardDraftLevel, rewardDraftXp));
	}

	public void syncRewardDraftFromPersistent()
	{
		RewardTuningState t = stateService.getState().getRewardTuning();
		rewardDraftFoil = t.getFoilChancePercent();
		rewardDraftKill = t.getKillCreditMultiplier();
		rewardDraftLevel = t.getLevelUpCreditMultiplier();
		rewardDraftXp = t.getXpCreditMultiplier();
	}

	public void stop()
	{
		welcomeContent.removeAll();
		overviewContent.removeAll();
		packsContent.removeAll();
		mainPanel.revalidate();
		mainPanel.repaint();
	}

	public void resetSessionUi()
	{
		refresh();
	}

	private void promptAndPerformCollectionReset()
	{
		int choice = JOptionPane.showConfirmDialog(
			this,
			"Are you sure you want to reset your collection and progress?",
			"Reset collection",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}
		performCollectionReset();
	}

	/** Same as {@code ::tcg-reset}: clears collection, credits, packs opened, reveal state, and XP credit baseline. */
	public void performCollectionReset()
	{
		stateService.resetAll();
		packRevealService.reset();
		clearPackRevealSidebarFreeze();
		creditAwardService.resetExperienceCreditBaseline();
		syncRewardDraftFromPersistent();
		selectedTab = Tab.WELCOME;
		resetSessionUi();
		if (client != null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Collection, credits, and opened packs have been reset.", null);
		}
	}

	public void refresh()
	{
		if (!panelVisible)
		{
			pendingRefresh = true;
			return;
		}

		if (!SwingUtilities.isEventDispatchThread())
		{
			queueRefreshOnEdt();
			return;
		}

		pendingRefresh = false;
		refreshNow();
	}

	/**
	 * After the pack reveal overlay closes, recomputes heavy overview stats off the EDT so the client thread returns
	 * quickly, then applies Swing updates on the EDT.
	 */
	public void refreshAfterPackRevealClose()
	{
		if (!panelVisible)
		{
			pendingRefresh = true;
			return;
		}
		if (packRevealService.isActive())
		{
			queueRefreshOnEdt();
			return;
		}
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshAfterPackRevealClose);
			return;
		}
		clearPackRevealSidebarFreeze();
		final long gen = packCloseRefreshGen.incrementAndGet();
		ForkJoinPool.commonPool().execute(() ->
		{
			try
			{
				PackCloseSnapshot snap = capturePackCloseSnapshot();
				List<CardDefinition> all = cardDatabase.getCards();
				List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(all);
				OverviewMetrics metrics = OverviewMetrics.compute(snap, all, rollPool);
				List<BoosterShopRow> shopRows = computeBoosterShopRows(snap, all, rollPool);
				SwingUtilities.invokeLater(() -> applyPackCloseRefresh(gen, snap, metrics, shopRows));
			}
			catch (Exception ex)
			{
				log.warn("Async overview refresh failed; falling back to EDT refresh", ex);
				SwingUtilities.invokeLater(() ->
				{
					if (gen == packCloseRefreshGen.get())
					{
						refresh();
					}
				});
			}
		});
	}

	private void applyPackCloseRefresh(long gen, PackCloseSnapshot snap, OverviewMetrics metrics, List<BoosterShopRow> shopRows)
	{
		if (gen != packCloseRefreshGen.get())
		{
			return;
		}
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> applyPackCloseRefresh(gen, snap, metrics, shopRows));
			return;
		}
		if (!panelVisible)
		{
			pendingRefresh = true;
			return;
		}
		pendingRefresh = false;
		ensureRootAttached();
		if (shouldShowLoggedOutPrompt())
		{
			footerPanel.setVisible(false);
			contentLayout.show(content, "LOGGED_OUT");
			mainPanel.revalidate();
			mainPanel.repaint();
			return;
		}
		footerPanel.setVisible(true);
		applyDefaultTabSelectionOnce();
		updateTabStyles();
		if (selectedTab == Tab.OVERVIEW)
		{
			overviewContent.removeAll();
			renderOverviewTabFromMetrics(overviewContent, snap, metrics, stateService.getState());
			contentLayout.show(content, Tab.OVERVIEW.name());
		}
		else if (selectedTab == Tab.SHOP)
		{
			packsContent.removeAll();
			renderPacksTabFromPackClose(packsContent, snap, shopRows);
			contentLayout.show(content, Tab.SHOP.name());
		}
		else if (selectedTab == Tab.WELCOME)
		{
			welcomeContent.removeAll();
			renderWelcomeTab(welcomeContent);
			contentLayout.show(content, Tab.WELCOME.name());
		}
		else
		{
			renderSelectedTab();
		}
		mainPanel.revalidate();
		mainPanel.repaint();
		SwingUtilities.invokeLater(collectionAlbumManager::refreshIfVisible);
	}

	private void queueRefreshOnEdt()
	{
		if (refreshQueued)
		{
			return;
		}

		refreshQueued = true;
		SwingUtilities.invokeLater(() ->
		{
			refreshQueued = false;
			refresh();
		});
	}

	private void refreshNow()
	{
		if (!packRevealService.isActive())
		{
			clearPackRevealSidebarFreeze();
		}
		ensureRootAttached();
		if (shouldShowLoggedOutPrompt())
		{
			footerPanel.setVisible(false);
			contentLayout.show(content, "LOGGED_OUT");
			mainPanel.revalidate();
			mainPanel.repaint();
			return;
		}
		footerPanel.setVisible(true);
		applyDefaultTabSelectionOnce();
		updateTabStyles();
		renderSelectedTab();
		mainPanel.revalidate();
		mainPanel.repaint();
		SwingUtilities.invokeLater(collectionAlbumManager::refreshIfVisible);
	}

	private void initializeTabContentPanel(JPanel panel)
	{
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
	}

	private void ensureRootAttached()
	{
		if (mainPanel.getComponentCount() == 0)
		{
			mainPanel.add(titlePanel, BorderLayout.NORTH);
			mainPanel.add(content, BorderLayout.CENTER);
			mainPanel.add(footerPanel, BorderLayout.SOUTH);
		}
	}

	private void applyDefaultTabSelectionOnce()
	{
		if (defaultTabSelectionInitialized)
		{
			return;
		}
		defaultTabSelectionInitialized = true;
		long openedPacks = stateService.getState().getEconomyState().getOpenedPacks();
		selectedTab = openedPacks == 0 ? Tab.WELCOME : Tab.OVERVIEW;
	}

	private void populateFooterPanel()
	{
		footerPanel.setLayout(new BoxLayout(footerPanel, BoxLayout.Y_AXIS));
		footerPanel.setOpaque(false);
		footerPanel.setBorder(new CompoundBorder(
			new MatteBorder(1, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(8, 0, 0, 0)
		));

		JPanel albumWrap = new JPanel(new BorderLayout(0, 0));
		albumWrap.setOpaque(false);
		JButton albumBtn = new JButton("Open collection album");
		albumBtn.setFont(FontManager.getRunescapeBoldFont());
		albumBtn.setFocusable(false);
		albumBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		albumBtn.setForeground(Color.WHITE);
		albumBtn.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(10, 14, 10, 14)
		));
		albumBtn.addActionListener(e -> collectionAlbumManager.showOrBringToFront());
		albumWrap.add(albumBtn, BorderLayout.CENTER);
		clampPanelWidth(albumWrap);
		footerPanel.add(albumWrap);

		footerPanel.add(Box.createRigidArea(new Dimension(0, 10)));

		JPanel resetWrap = new JPanel(new BorderLayout(0, 0));
		resetWrap.setOpaque(false);
		JButton resetCollectionBtn = new JButton("Reset collection");
		resetCollectionBtn.setFont(FontManager.getRunescapeSmallFont());
		resetCollectionBtn.setFocusable(false);
		resetCollectionBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		resetCollectionBtn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		resetCollectionBtn.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(8, 12, 8, 12)
		));
		resetCollectionBtn.addActionListener(e -> promptAndPerformCollectionReset());
		resetWrap.add(resetCollectionBtn, BorderLayout.CENTER);
		clampPanelWidth(resetWrap);
		footerPanel.add(resetWrap);
	}

	private boolean shouldShowLoggedOutPrompt()
	{
		if (!isShowing())
		{
			return false;
		}
		return !isClientInGameWorld();
	}

	/**
	 * {@link GameState#LOGGED_IN} is the normal case; {@link Client#getLocalPlayer()} covers brief or client-specific
	 * states where the game world is active but {@code getGameState()} is not yet {@code LOGGED_IN}.
	 */
	private boolean isClientInGameWorld()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			return true;
		}
		return client.getLocalPlayer() != null;
	}

	private JPanel buildTitlePanel()
	{
		JPanel title = new JPanel();
		title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
		title.setOpaque(false);

		JPanel titleLabelWrapper = new JPanel(new BorderLayout());
		titleLabelWrapper.setOpaque(false);
		JLabel titleLabel = new JLabel("OSRS TCG");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(0, 12, 2, 12)
		));
		titleLabelWrapper.add(titleLabel, BorderLayout.CENTER);

		JPanel tabWrapper = new JPanel(new GridLayout(1, 3));
		tabWrapper.setOpaque(false);
		tabWrapper.setBorder(new EmptyBorder(2, 0, 0, 0));
		tabWrapper.add(configureTabButton(welcomeTabButton, Tab.WELCOME));
		tabWrapper.add(configureTabButton(overviewTabButton, Tab.OVERVIEW));
		tabWrapper.add(configureTabButton(shopTabButton, Tab.SHOP));

		title.add(titleLabelWrapper);
		title.add(tabWrapper);
		return title;
	}

	private JButton configureTabButton(JButton button, Tab tab)
	{
		button.setText(tab.getLabel());
		button.setForeground(selectedTab == tab ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setContentAreaFilled(false);
		button.setOpaque(false);
		button.setFocusable(false);
		button.setBorderPainted(false);
		button.setBorder(tabBorder(selectedTab == tab));
		button.setFocusPainted(false);
		button.addActionListener(e ->
		{
			if (selectedTab != tab)
			{
				selectedTab = tab;
				updateTabStyles();
				refresh();
			}
		});
		return button;
	}

	private void updateTabStyles()
	{
		applyTabStyle(welcomeTabButton, selectedTab == Tab.WELCOME);
		applyTabStyle(overviewTabButton, selectedTab == Tab.OVERVIEW);
		applyTabStyle(shopTabButton, selectedTab == Tab.SHOP);
	}

	private void applyTabStyle(JButton button, boolean active)
	{
		button.setForeground(active ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		button.setBorder(tabBorder(active));
	}

	private void renderSelectedTab()
	{
		if (packRevealService.isActive() && sidebarRevealSpoilerFreeze != null)
		{
			JPanel activePanel = panelForTab(selectedTab);
			if (selectedTab == Tab.WELCOME)
			{
				if (!welcomeBuiltForActiveReveal)
				{
					activePanel.removeAll();
					renderWelcomeTab(activePanel);
					welcomeBuiltForActiveReveal = true;
				}
				contentLayout.show(content, selectedTab.name());
				return;
			}
			if (selectedTab == Tab.OVERVIEW)
			{
				if (!overviewBuiltForActiveReveal)
				{
					activePanel.removeAll();
					renderOverviewTab(activePanel);
					overviewBuiltForActiveReveal = true;
				}
				contentLayout.show(content, selectedTab.name());
				return;
			}
			if (selectedTab == Tab.SHOP)
			{
				if (!shopBuiltForActiveReveal)
				{
					activePanel.removeAll();
					renderPacksTab(activePanel);
					shopBuiltForActiveReveal = true;
				}
				contentLayout.show(content, selectedTab.name());
				return;
			}
			log.warn("Unsupported tab {}", selectedTab);
			contentLayout.show(content, selectedTab.name());
			return;
		}

		JPanel activePanel = panelForTab(selectedTab);
		activePanel.removeAll();
		switch (selectedTab)
		{
			case WELCOME:
				renderWelcomeTab(activePanel);
				break;
			case OVERVIEW:
				renderOverviewTab(activePanel);
				break;
			case SHOP:
				renderPacksTab(activePanel);
				break;
			default:
				log.warn("Unsupported tab {}", selectedTab);
		}
		contentLayout.show(content, selectedTab.name());
	}

	private JPanel panelForTab(Tab tab)
	{
		switch (tab)
		{
			case WELCOME:
				return welcomeContent;
			case OVERVIEW:
				return overviewContent;
			case SHOP:
				return packsContent;
			default:
				return overviewContent;
		}
	}

	private void renderWelcomeTab(JPanel target)
	{
		int w = liveSidebarContentWidth();
		target.add(buildTcgWelcomeBlurb(w));
		target.add(Box.createVerticalGlue());
	}

	private void renderOverviewTab(JPanel target)
	{
		PackCloseSnapshot snap = capturePackCloseSnapshotForDisplay();
		List<CardDefinition> all = cardDatabase.getCards();
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(all);
		OverviewMetrics metrics = OverviewMetrics.compute(snap, all, rollPool);
		renderOverviewTabFromMetrics(target, snap, metrics, stateService.getState());
	}

	private List<BoosterShopRow> computeBoosterShopRows(PackCloseSnapshot snap, List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
	{
		Set<String> collectedNames = collectedNamesFromOwned(snap.owned);
		List<BoosterPackDefinition> boosters = new ArrayList<>(packCatalog.getBoosters());
		if (stateService.isDebugLogging() && boosters.stream().noneMatch(PackOpeningService::isDebugPack))
		{
			boosters.add(createDebugBoosterPackDefinition());
		}
		List<BoosterShopRow> out = new ArrayList<>(boosters.size());
		for (BoosterPackDefinition booster : boosters)
		{
			if (booster == null)
			{
				continue;
			}
			int[] p = shopProgressOwnedTotal(booster, allCards, rollPool, collectedNames);
			out.add(new BoosterShopRow(booster, p[0], p[1]));
		}
		return out;
	}

	private void renderPacksTabFromPackClose(JPanel target, PackCloseSnapshot snap, List<BoosterShopRow> shopRows)
	{
		target.add(statPanel("Current credits", format(snap.credits)));
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		target.add(sellDuplicatesPanel());
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		target.add(boosterShopPanelFromPrecalc(snap.credits, shopRows));
	}

	private JPanel boosterShopPanelFromPrecalc(long credits, List<BoosterShopRow> rows)
	{
		JPanel outer = new JPanel();
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setOpaque(false);

		if (rows == null || rows.isEmpty())
		{
			outer.add(infoPanel("No booster packs configured. Add Packs.json to plugin resources."));
			clampPanelWidth(outer);
			return outer;
		}

		JPanel grid = new JPanel(new GridLayout(0, 2, SHOP_BOOSTER_GRID_GAP, SHOP_BOOSTER_GRID_GAP));
		grid.setOpaque(false);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		for (BoosterShopRow row : rows)
		{
			if (row == null || row.booster == null)
			{
				continue;
			}
			JButton buy = createBoosterBuyButton(row.booster, row.progressOwn, row.progressTotal);
			int price = row.booster.getPrice();
			buy.setEnabled(credits >= price);
			grid.add(buy);
		}

		outer.add(grid);
		clampPanelWidth(outer);
		return outer;
	}

	private PackCloseSnapshot capturePackCloseSnapshot()
	{
		synchronized (stateService)
		{
			TcgState s = stateService.getState();
			return new PackCloseSnapshot(
				new HashMap<>(s.getCollectionState().getOwnedCards()),
				s.getEconomyState().getCredits(),
				s.getEconomyState().getOpenedPacks());
		}
	}

	/**
	 * Snapshot used for sidebar stats: frozen pre-pull state while the pack overlay is open, otherwise live state.
	 */
	private PackCloseSnapshot capturePackCloseSnapshotForDisplay()
	{
		if (sidebarRevealSpoilerFreeze != null && packRevealService.isActive())
		{
			return sidebarRevealSpoilerFreeze;
		}
		return capturePackCloseSnapshot();
	}

	public void beginPackRevealSidebarFreeze()
	{
		sidebarRevealSpoilerFreeze = capturePackCloseSnapshot();
		welcomeBuiltForActiveReveal = false;
		overviewBuiltForActiveReveal = false;
		shopBuiltForActiveReveal = false;
	}

	public void clearPackRevealSidebarFreeze()
	{
		sidebarRevealSpoilerFreeze = null;
		welcomeBuiltForActiveReveal = false;
		overviewBuiltForActiveReveal = false;
		shopBuiltForActiveReveal = false;
	}

	private void renderOverviewTabFromMetrics(JPanel target, PackCloseSnapshot snap, OverviewMetrics m, TcgState state)
	{
		target.add(statPanel("Credits", format(snap.credits)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Opened packs", format(snap.openedPacks)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Unique cards", format(m.uniqueOwned) + " / " + format(m.totalCardPool)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Total cards", format(m.totalCardsOwned)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Foil cards", format(m.foilOwned)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Collection %", String.format("%.1f%%", m.completionPct)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Collection score", format(m.collectionScore)));
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		addRewardTuningOverviewSection(target, state);
	}

	private static final class BoosterShopRow
	{
		private final BoosterPackDefinition booster;
		private final int progressOwn;
		private final int progressTotal;

		private BoosterShopRow(BoosterPackDefinition booster, int progressOwn, int progressTotal)
		{
			this.booster = booster;
			this.progressOwn = progressOwn;
			this.progressTotal = progressTotal;
		}
	}

	private static final class PackCloseSnapshot
	{
		private final Map<CardCollectionKey, Integer> owned;
		private final long credits;
		private final long openedPacks;

		private PackCloseSnapshot(Map<CardCollectionKey, Integer> owned, long credits, long openedPacks)
		{
			this.owned = owned;
			this.credits = credits;
			this.openedPacks = openedPacks;
		}
	}

	private static final class OverviewMetrics
	{
		private final int uniqueOwned;
		private final int totalCardsOwned;
		private final long foilOwned;
		private final int totalCardPool;
		private final double completionPct;
		private final long collectionScore;

		private OverviewMetrics(int uniqueOwned, int totalCardsOwned, long foilOwned, int totalCardPool,
			double completionPct, long collectionScore)
		{
			this.uniqueOwned = uniqueOwned;
			this.totalCardsOwned = totalCardsOwned;
			this.foilOwned = foilOwned;
			this.totalCardPool = totalCardPool;
			this.completionPct = completionPct;
			this.collectionScore = collectionScore;
		}

		private static OverviewMetrics compute(PackCloseSnapshot snap, List<CardDefinition> allCards,
			List<CardDefinition> rollPool)
		{
			Set<String> rollPoolNames = new HashSet<>();
			for (CardDefinition c : rollPool)
			{
				if (c != null && c.getName() != null)
				{
					rollPoolNames.add(c.getName());
				}
			}

			Map<CardCollectionKey, Integer> owned = snap.owned;
			int uniqueOwned = (int) owned.keySet().stream()
				.filter(k -> k.getCardName() != null && rollPoolNames.contains(k.getCardName()))
				.count();
			int totalCardsOwned = owned.entrySet().stream()
				.filter(e -> e.getKey().getCardName() != null && rollPoolNames.contains(e.getKey().getCardName()))
				.mapToInt(e -> e.getValue() == null ? 0 : e.getValue())
				.sum();
			long foilOwned = owned.entrySet().stream()
				.filter(e -> e.getKey().isFoil()
					&& e.getKey().getCardName() != null
					&& rollPoolNames.contains(e.getKey().getCardName()))
				.mapToLong(e -> e.getValue() == null ? 0L : e.getValue())
				.sum();
			int totalCardPool = rollPool.size();
			double completion = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueOwned) / totalCardPool;

			Set<String> collectedNames = collectedNamesFromOwned(owned);
			Map<String, CardDefinition> defByLower = new HashMap<>();
			for (CardDefinition c : allCards)
			{
				if (c != null && c.getName() != null)
				{
					defByLower.putIfAbsent(c.getName().toLowerCase(Locale.ROOT), c);
				}
			}
			long collectionScore = 0L;
			for (String cardName : collectedNames)
			{
				if (cardName == null || !rollPoolNames.contains(cardName))
				{
					continue;
				}
				CardDefinition def = defByLower.get(cardName.toLowerCase(Locale.ROOT));
				if (def == null)
				{
					continue;
				}
				boolean hasFoil = hasFoilOwned(owned, cardName);
				collectionScore += hasFoil ? RarityMath.foilAdjustedScoreRounded(def) : Math.round(RarityMath.score(def));
			}
			return new OverviewMetrics(uniqueOwned, totalCardsOwned, foilOwned, totalCardPool, completion, collectionScore);
		}
	}

	private void addRewardTuningOverviewSection(JPanel target, TcgState state)
	{
		boolean locked = stateService.isRewardTuningLocked();
		RewardTuningState tuning = state.getRewardTuning();
		int fullW = liveSidebarContentWidth();
		int contentW = Math.max(160, fullW - 12);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setBorder(new CompoundBorder(
			new MatteBorder(1, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(8, 0, 0, 0)
		));

		JLabel heading = new JLabel("Multipliers");
		heading.setForeground(Color.WHITE);
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setAlignmentX(LEFT_ALIGNMENT);
		if (locked)
		{
			heading.setToolTipText(REWARD_TUNING_LOCKED_TOOLTIP);
		}
		section.add(heading);
		section.add(Box.createRigidArea(new Dimension(0, 6)));

		if (locked)
		{
			JSpinner foilSpin = new JSpinner(new SpinnerNumberModel(tuning.getFoilChancePercent(), 0, 100, 1));
			JSpinner killSpin = new JSpinner(new SpinnerNumberModel(tuning.getKillCreditMultiplier(), 0.0d, 10.0d, 0.1d));
			JSpinner levelSpin = new JSpinner(new SpinnerNumberModel(tuning.getLevelUpCreditMultiplier(), 0.0d, 10.0d, 0.1d));
			JSpinner xpSpin = new JSpinner(new SpinnerNumberModel(tuning.getXpCreditMultiplier(), 0.0d, 10.0d, 0.1d));
			styleRewardTuningSpinner(foilSpin, 3);
			styleRewardTuningSpinner(killSpin, 5);
			styleRewardTuningSpinner(levelSpin, 5);
			styleRewardTuningSpinner(xpSpin, 5);
			foilSpin.setEnabled(false);
			killSpin.setEnabled(false);
			levelSpin.setEnabled(false);
			xpSpin.setEnabled(false);
			foilSpin.setToolTipText(REWARD_TUNING_LOCKED_TOOLTIP);
			killSpin.setToolTipText(REWARD_TUNING_LOCKED_TOOLTIP);
			levelSpin.setToolTipText(REWARD_TUNING_LOCKED_TOOLTIP);
			xpSpin.setToolTipText(REWARD_TUNING_LOCKED_TOOLTIP);
			section.add(buildMultiplierGrid(contentW, levelSpin, killSpin, xpSpin, foilSpin));
			if (stateService.isDebugLogging())
			{
				section.add(Box.createRigidArea(new Dimension(0, 8)));
				JLabel debugBanner = new JLabel("DEBUG MODE");
				debugBanner.setForeground(Color.RED);
				debugBanner.setFont(FontManager.getRunescapeBoldFont());
				debugBanner.setAlignmentX(LEFT_ALIGNMENT);
				section.add(debugBanner);
			}
			finishRewardTuningSectionLayout(section);
			target.add(section);
			return;
		}

		JSpinner foilSpin = new JSpinner(new SpinnerNumberModel(rewardDraftFoil, 0, 100, 1));
		JSpinner killSpin = new JSpinner(new SpinnerNumberModel(rewardDraftKill, 0.0d, 10.0d, 0.1d));
		JSpinner levelSpin = new JSpinner(new SpinnerNumberModel(rewardDraftLevel, 0.0d, 10.0d, 0.1d));
		JSpinner xpSpin = new JSpinner(new SpinnerNumberModel(rewardDraftXp, 0.0d, 10.0d, 0.1d));
		styleRewardTuningSpinner(foilSpin, 3);
		styleRewardTuningSpinner(killSpin, 5);
		styleRewardTuningSpinner(levelSpin, 5);
		styleRewardTuningSpinner(xpSpin, 5);

		foilSpin.addChangeListener(e ->
		{
			rewardDraftFoil = ((Number) foilSpin.getValue()).intValue();
			SwingUtilities.invokeLater(this::refresh);
		});
		killSpin.addChangeListener(e ->
		{
			rewardDraftKill = ((Number) killSpin.getValue()).doubleValue();
			SwingUtilities.invokeLater(this::refresh);
		});
		levelSpin.addChangeListener(e ->
		{
			rewardDraftLevel = ((Number) levelSpin.getValue()).doubleValue();
			SwingUtilities.invokeLater(this::refresh);
		});
		xpSpin.addChangeListener(e ->
		{
			rewardDraftXp = ((Number) xpSpin.getValue()).doubleValue();
			SwingUtilities.invokeLater(this::refresh);
		});

		section.add(buildMultiplierGrid(contentW, levelSpin, killSpin, xpSpin, foilSpin));
		if (stateService.isDebugLogging() || !rewardDraftMatchesPluginDefaults())
		{
			section.add(buildMultiplierTradingWarningTextArea(contentW));
		}
		if (runeliteDeveloperMode)
		{
			section.add(Box.createRigidArea(new Dimension(0, 8)));
			section.add(buildDebugModeCheckbox());
		}
		finishRewardTuningSectionLayout(section);
		target.add(section);
	}

	private JCheckBox buildDebugModeCheckbox()
	{
		JCheckBox cb = new JCheckBox("Debug mode");
		cb.setOpaque(false);
		cb.setForeground(Color.WHITE);
		cb.setFont(FontManager.getRunescapeSmallFont());
		cb.setAlignmentX(LEFT_ALIGNMENT);
		cb.setSelected(stateService.isDebugLogging());
		cb.setToolTipText("Extra logging, credit chat lines, free debug booster, ::tcg-set, ::tcg-give, and ::tcg-apex.");
		cb.addActionListener(e -> persistDebugLogging(cb.isSelected()));
		return cb;
	}

	private boolean rewardDraftMatchesPluginDefaults()
	{
		RewardTuningState d = RewardTuningState.DEFAULTS;
		if (rewardDraftFoil != d.getFoilChancePercent())
		{
			return false;
		}
		return multiplierCloseToDefault(rewardDraftKill, d.getKillCreditMultiplier())
			&& multiplierCloseToDefault(rewardDraftLevel, d.getLevelUpCreditMultiplier())
			&& multiplierCloseToDefault(rewardDraftXp, d.getXpCreditMultiplier());
	}

	private static boolean multiplierCloseToDefault(double value, double defaultValue)
	{
		return Double.compare(value, defaultValue) == 0 || Math.abs(value - defaultValue) < 1e-9d;
	}

	private static JTextArea buildMultiplierTradingWarningTextArea(int contentMaxW)
	{
		int w = Math.max(120, contentMaxW);
		JTextArea ta = new JTextArea(REWARD_TUNING_NON_DEFAULT_TRADE_WARNING);
		ta.setEditable(false);
		ta.setOpaque(false);
		ta.setFocusable(false);
		ta.setForeground(Color.RED);
		ta.setFont(FontManager.getRunescapeSmallFont());
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setBorder(new EmptyBorder(6, 0, 0, 0));
		ta.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		ta.setSize(w, Short.MAX_VALUE);
		int bodyH = ta.getPreferredSize().height;
		ta.setPreferredSize(new Dimension(w, bodyH));
		ta.setMaximumSize(new Dimension(w, bodyH));
		return ta;
	}

	private static JPanel buildTcgWelcomeBlurb(int contentMaxW)
	{
		int w = Math.max(120, contentMaxW);

		JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setOpaque(false);
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.setBorder(new EmptyBorder(8, 0, 0, 0));

		JLabel head = new JLabel(TCG_WELCOME_HEADER);
		head.setForeground(Color.WHITE);
		head.setFont(FontManager.getRunescapeBoldFont());
		head.setAlignmentX(LEFT_ALIGNMENT);
		wrap.add(head);

		JTextArea ta = new JTextArea(TCG_WELCOME_BODY);
		ta.setEditable(false);
		ta.setOpaque(false);
		ta.setFocusable(false);
		ta.setForeground(new Color(0xBBBBBB));
		ta.setFont(FontManager.getRunescapeSmallFont());
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setBorder(new EmptyBorder(6, 0, 0, 0));
		ta.setAlignmentX(LEFT_ALIGNMENT);
		ta.setSize(w, Short.MAX_VALUE);
		int bodyH = ta.getPreferredSize().height;
		ta.setPreferredSize(new Dimension(w, bodyH));
		ta.setMaximumSize(new Dimension(w, bodyH));
		wrap.add(ta);

		JTextArea tcgCmd = new JTextArea(TCG_WELCOME_TCG_COMMAND_BODY);
		tcgCmd.setEditable(false);
		tcgCmd.setOpaque(false);
		tcgCmd.setFocusable(false);
		tcgCmd.setForeground(new Color(0xBBBBBB));
		tcgCmd.setFont(FontManager.getRunescapeSmallFont());
		tcgCmd.setLineWrap(true);
		tcgCmd.setWrapStyleWord(true);
		tcgCmd.setBorder(new EmptyBorder(10, 0, 0, 0));
		tcgCmd.setAlignmentX(LEFT_ALIGNMENT);
		tcgCmd.setSize(w, Short.MAX_VALUE);
		int tcgCmdH = tcgCmd.getPreferredSize().height;
		tcgCmd.setPreferredSize(new Dimension(w, tcgCmdH));
		tcgCmd.setMaximumSize(new Dimension(w, tcgCmdH));
		wrap.add(tcgCmd);

		int totalH = wrap.getPreferredSize().height;
		wrap.setPreferredSize(new Dimension(w, totalH));
		wrap.setMaximumSize(new Dimension(w, totalH));
		return wrap;
	}

	private void persistDebugLogging(boolean enabled)
	{
		stateService.setDebugLogging(enabled);
		refresh();
	}

	/** Usable width inside the plugin sidebar (matches shop grid / booster buttons). */
	private static int sidebarInnerWidth()
	{
		return Math.max(160, PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET);
	}

	/**
	 * Horizontal space for tab content: prefers live {@link #getWidth()} so resizable sidebars fill correctly;
	 * falls back to {@link #sidebarInnerWidth()} before the panel is laid out.
	 */
	private int liveSidebarContentWidth()
	{
		Insets pi = getInsets();
		int raw = getWidth() - pi.left - pi.right;
		if (raw <= 0)
		{
			return sidebarInnerWidth();
		}
		int mainPanelHorizontalPad = 12;
		return Math.max(sidebarInnerWidth(), raw - mainPanelHorizontalPad);
	}

	private static JPanel buildMultiplierGrid(int contentW, JSpinner levelSpin, JSpinner killSpin, JSpinner xpSpin,
		JSpinner foilSpin)
	{
		int colW = Math.max(72, (contentW - 8) / 2);
		JPanel multGrid = new JPanel(new GridLayout(2, 2, 8, 6));
		multGrid.setOpaque(false);
		multGrid.setAlignmentX(LEFT_ALIGNMENT);
		multGrid.add(rewardTuningEditableRow("Level up multiplier", levelSpin, colW));
		multGrid.add(rewardTuningEditableRow("Kill multiplier", killSpin, colW));
		multGrid.add(rewardTuningEditableRow("XP multiplier", xpSpin, colW));
		multGrid.add(rewardTuningEditableRow("Foil chance (%)", foilSpin, colW));
		multGrid.setPreferredSize(new Dimension(contentW, multGrid.getPreferredSize().height));
		multGrid.setMaximumSize(new Dimension(contentW, multGrid.getPreferredSize().height));
		return multGrid;
	}

	private static void finishRewardTuningSectionLayout(JPanel section)
	{
		section.setAlignmentX(LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	private static JPanel rewardTuningEditableRow(String labelText, JSpinner spinner, int contentMaxW)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		String shortLabel = labelText.length() > 36 ? labelText.substring(0, 33) + "..." : labelText;
		JLabel label = new JLabel(shortLabel);
		label.setToolTipText(labelText);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(LEFT_ALIGNMENT);
		row.add(label);

		spinner.setAlignmentX(LEFT_ALIGNMENT);
		int spinW = Math.min(96, Math.max(56, contentMaxW - 8));
		Dimension sp = spinner.getPreferredSize();
		Dimension spinDim = new Dimension(spinW, sp.height);
		spinner.setMinimumSize(spinDim);
		spinner.setPreferredSize(spinDim);
		spinner.setMaximumSize(spinDim);
		row.add(spinner);

		row.setAlignmentX(LEFT_ALIGNMENT);
		int rowH = row.getPreferredSize().height;
		row.setMinimumSize(new Dimension(0, rowH));
		row.setPreferredSize(new Dimension(contentMaxW, rowH));
		row.setMaximumSize(new Dimension(contentMaxW, rowH));
		return row;
	}

	private static void styleRewardTuningSpinner(JSpinner spinner, int editorColumns)
	{
		spinner.setFont(FontManager.getRunescapeSmallFont());
		spinner.setAlignmentX(LEFT_ALIGNMENT);
		JComponent editor = spinner.getEditor();
		if (editor instanceof JSpinner.DefaultEditor)
		{
			((JSpinner.DefaultEditor) editor).getTextField().setColumns(editorColumns);
		}
	}

	/** Distinct card names with combined foil + non-foil quantity ≥ 1. */
	private static Set<String> collectedNamesFromOwned(Map<CardCollectionKey, Integer> owned)
	{
		Map<String, Integer> ownedQtyByName = new HashMap<>();
		for (Map.Entry<CardCollectionKey, Integer> entry : owned.entrySet())
		{
			String cardName = entry.getKey().getCardName();
			if (cardName == null)
			{
				continue;
			}
			int qty = entry.getValue() == null ? 0 : entry.getValue();
			ownedQtyByName.merge(cardName, qty, Integer::sum);
		}
		Set<String> collectedNames = new HashSet<>();
		for (Map.Entry<String, Integer> entry : ownedQtyByName.entrySet())
		{
			if (entry.getValue() != null && entry.getValue() > 0)
			{
				collectedNames.add(entry.getKey());
			}
		}
		return collectedNames;
	}

	/**
	 * Shop progress: Standard (empty category) = distinct names in the roll pool; regional = distinct names in the full
	 * catalog that match {@link BoosterPackDefinition#cardMatchesRegion} (same OR / AND rules as pack filters).
	 */
	private static int[] shopProgressOwnedTotal(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool,
		Set<String> collectedNames)
	{
		List<String> filters = booster.getCategoryFilters();
		Set<String> eligible = new HashSet<>();
		if (filters.isEmpty())
		{
			for (CardDefinition c : rollPool)
			{
				if (c == null || c.getName() == null || c.getName().trim().isEmpty())
				{
					continue;
				}
				eligible.add(c.getName().trim());
			}
		}
		else
		{
			for (CardDefinition c : allCards)
			{
				if (c == null || c.getName() == null || c.getName().trim().isEmpty())
				{
					continue;
				}
				if (BoosterPackDefinition.cardMatchesRegion(c, filters))
				{
					eligible.add(c.getName().trim());
				}
			}
		}
		int total = eligible.size();
		int own = (int) eligible.stream().filter(collectedNames::contains).count();
		return new int[] { own, total };
	}

	private void renderPacksTab(JPanel target)
	{
		PackCloseSnapshot displaySnap = capturePackCloseSnapshotForDisplay();
		target.add(statPanel("Current credits", format(displaySnap.credits)));
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		target.add(sellDuplicatesPanel());
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		target.add(boosterShopPanel(displaySnap));
	}

	private JPanel statPanel(String labelText, String valueText)
	{
		return statPanel(labelText, valueText, Color.WHITE);
	}

	private JPanel statPanel(String labelText, String valueText, Color labelColor)
	{
		JPanel panel = new JPanel(new BorderLayout(8, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(4, 6, 4, 6));

		JLabel label = textPanel(shorten(labelText, 24));
		label.setToolTipText(labelText);
		label.setForeground(labelColor == null ? Color.WHITE : labelColor);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		JLabel value = textPanel(valueText);
		value.setHorizontalAlignment(SwingConstants.RIGHT);

		panel.add(label, BorderLayout.CENTER);
		panel.add(value, BorderLayout.EAST);
		clampPanelWidth(panel);
		return panel;
	}

	private JPanel infoPanel(String message)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(6, 6, 6, 6));
		JLabel label = textPanel(message);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		panel.add(label, BorderLayout.CENTER);
		clampPanelWidth(panel);
		return panel;
	}

	private JLabel textPanel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	private static final int SHOP_BOOSTER_GRID_GAP = 6;

	private static BoosterPackDefinition createDebugBoosterPackDefinition()
	{
		BoosterPackDefinition b = new BoosterPackDefinition();
		b.setId(PackOpeningService.DEBUG_PACK_ID);
		b.setName("Debug pack (5× same)");
		b.setPrice(0);
		b.setCategory(null);
		return b;
	}

	private JPanel boosterShopPanel(PackCloseSnapshot displaySnap)
	{
		JPanel outer = new JPanel();
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setOpaque(false);

		List<BoosterPackDefinition> boosters = new ArrayList<>(packCatalog.getBoosters());
		if (stateService.isDebugLogging()
			&& boosters.stream().noneMatch(b -> PackOpeningService.isDebugPack(b)))
		{
			boosters.add(createDebugBoosterPackDefinition());
		}
		if (boosters.isEmpty())
		{
			outer.add(infoPanel("No booster packs configured. Add Packs.json to plugin resources."));
			clampPanelWidth(outer);
			return outer;
		}

		Map<CardCollectionKey, Integer> owned = displaySnap.owned;
		Set<String> collectedNames = collectedNamesFromOwned(owned);
		List<CardDefinition> allCards = cardDatabase.getCards();
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(allCards);

		JPanel grid = new JPanel(new GridLayout(0, 2, SHOP_BOOSTER_GRID_GAP, SHOP_BOOSTER_GRID_GAP));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);

		boolean revealBusy = packRevealService.isActive();
		long credits = displaySnap.credits;
		for (BoosterPackDefinition booster : boosters)
		{
			JButton buy = createBoosterBuyButton(booster, allCards, rollPool, collectedNames);
			int price = booster.getPrice();
			buy.setEnabled(!revealBusy && credits >= price);
			grid.add(buy);
		}

		outer.add(grid);
		clampPanelWidth(outer);
		return outer;
	}

	private int shopBoosterButtonWidth()
	{
		int inner = liveSidebarContentWidth();
		return Math.max(96, (inner - SHOP_BOOSTER_GRID_GAP) / 2);
	}

	private JButton createBoosterBuyButton(BoosterPackDefinition booster, int progressOwn, int progressTotal)
	{
		int price = booster.getPrice();
		String title = booster.getName() == null ? "Booster" : booster.getName();
		String progressLine = format(progressOwn) + " / " + format(progressTotal);
		JButton button = new JButton("<html><div style='text-align:center'>" + htmlEscape(title)
			+ "<br/>" + format(price) + " credits"
			+ "<br/><br/>" + progressLine + "</div></html>");
		button.setIcon(null);
		button.setHorizontalTextPosition(SwingConstants.CENTER);
		button.setVerticalTextPosition(SwingConstants.CENTER);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setForeground(Color.WHITE);
		button.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(6, 6, 6, 6)
		));
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusable(false);
		button.setPreferredSize(new Dimension(shopBoosterButtonWidth(), 88));

		button.addActionListener(e ->
		{
			if (packRevealService.isActive())
			{
				refresh();
				return;
			}
			beginPackRevealSidebarFreeze();
			var preOwned = new java.util.HashSet<>(stateService.getState().getCollectionState().getOwnedCards().keySet());
			boolean showScrollWheelHint = stateService.getState().getEconomyState().getOpenedPacks() == 0L;
			var result = packOpeningService.buyAndOpenPack(booster);
			if (!result.isSuccess() || result.getPulls() == null)
			{
				clearPackRevealSidebarFreeze();
				refresh();
				return;
			}
			packRevealService.startReveal(result.getPulls(), preOwned, result.getBoosterDisplayName(),
				result.getBoosterPackId(), showScrollWheelHint, result.isApexPack());
			refresh();
		});
		return button;
	}

	private JButton createBoosterBuyButton(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool,
		Set<String> collectedNames)
	{
		int[] progress = shopProgressOwnedTotal(booster, allCards, rollPool, collectedNames);
		return createBoosterBuyButton(booster, progress[0], progress[1]);
	}

	private JPanel sellDuplicatesPanel()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(6, 6, 6, 6));
		panel.add(sellDuplicatesButton, BorderLayout.CENTER);
		clampPanelWidth(panel);
		return panel;
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
		button.addActionListener(ev ->
		{
			TcgState current = stateService.getState();
			List<OwnedCardInstance> all = new ArrayList<>(current.getCollectionState().getOwnedInstances());
			if (all.isEmpty())
			{
				refresh();
				return;
			}

			Map<String, List<OwnedCardInstance>> byName = new HashMap<>();
			for (OwnedCardInstance i : all)
			{
				String n = i.getCardName();
				byName.computeIfAbsent(n, k -> new ArrayList<>()).add(i);
			}

			List<OwnedCardInstance> kept = new ArrayList<>();
			long creditsToAdd = 0L;

			for (Map.Entry<String, List<OwnedCardInstance>> entry : byName.entrySet())
			{
				String name = entry.getKey();
				List<OwnedCardInstance> lst = entry.getValue();
				int qF = (int) lst.stream().filter(OwnedCardInstance::isFoil).count();
				int qN = lst.size() - qF;
				int total = qF + qN;
				if (total <= 1)
				{
					kept.addAll(lst);
					continue;
				}

				long score = scoreForCard(name);
				if (qF > 0)
				{
					lst.stream()
						.filter(OwnedCardInstance::isFoil)
						.max(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs))
						.ifPresent(kept::add);
					int sold = (qF - 1) + qN;
					if (sold > 0)
					{
						creditsToAdd += (score / 1000L) * sold;
					}
				}
				else
				{
					lst.stream()
						.filter(i -> !i.isFoil())
						.max(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs))
						.ifPresent(kept::add);
					int sold = qF + (qN - 1);
					if (sold > 0)
					{
						creditsToAdd += (score / 1000L) * sold;
					}
				}
			}

			stateService.setCollectionInstances(kept);
			if (creditsToAdd > 0L)
			{
				stateService.addCredits(creditsToAdd);
			}
			refresh();
		});
		return button;
	}

	private static String format(long value)
	{
		return NumberFormatting.format(value);
	}

	private void rebuildRarityColorMap()
	{
		scoreByCardName.clear();
		Map<String, List<CardDefinition>> byCategory = cardDatabase.getCards().stream()
			.filter(card -> card.getName() != null && !card.getName().trim().isEmpty())
			.collect(Collectors.groupingBy(CardDefinition::getPrimaryCategory));

		for (List<CardDefinition> pool : byCategory.values())
		{
			if (pool.isEmpty())
			{
				continue;
			}

			List<CardDefinition> sorted = new ArrayList<>(pool);
			sorted.sort((a, b) -> Double.compare(displayScore(a), displayScore(b)));
			int size = sorted.size();
			for (int i = 0; i < size; i++)
			{
				CardDefinition card = sorted.get(i);
				scoreByCardName.put(card.getName().toLowerCase(), Math.round(displayScore(card)));
			}
		}
	}

	private long scoreForCard(String cardName)
	{
		if (cardName == null)
		{
			return 0L;
		}
		return scoreByCardName.getOrDefault(cardName.toLowerCase(), 0L);
	}

	private static boolean hasFoilOwned(Map<CardCollectionKey, Integer> owned, String cardName)
	{
		if (cardName == null)
		{
			return false;
		}
		Integer n = owned.get(new CardCollectionKey(cardName, true));
		return n != null && n > 0;
	}

	private double displayScore(CardDefinition card)
	{
		return RarityMath.score(card);
	}

	private static String htmlEscape(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private String shorten(String value, int maxLen)
	{
		if (value == null || value.length() <= maxLen)
		{
			return value;
		}
		if (maxLen <= 3)
		{
			return value.substring(0, Math.max(0, maxLen));
		}
		return value.substring(0, maxLen - 3) + "...";
	}

	private javax.swing.border.Border tabBorder(boolean active)
	{
		Color borderColor = active ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.DARKER_GRAY_HOVER_COLOR;
		return new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, borderColor),
			new EmptyBorder(3, 0, 3, 0)
		);
	}

	private void clampPanelWidth(JPanel panel)
	{
		panel.setAlignmentX(LEFT_ALIGNMENT);
		Dimension preferred = panel.getPreferredSize();
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
	}
}

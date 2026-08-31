package com.osrstcg.ui;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.shop.CloudPackService;
import com.osrstcg.cloud.shop.CloudSellService;
import com.osrstcg.cloud.trade.TradeCloudService;
import com.osrstcg.pack.PackOpenCoordinator;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.CollectionState;
import com.osrstcg.state.TcgState;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.account.AccountPanelLauncher;
import com.osrstcg.ui.account.CreateProfileController;
import com.osrstcg.ui.account.SidebarNoticeView;
import com.osrstcg.ui.collection.CollectionListModel;
import com.osrstcg.ui.collection.CollectionTab;
import com.osrstcg.ui.layout.PackCloseSnapshot;
import com.osrstcg.ui.layout.SidebarChrome;
import com.osrstcg.ui.layout.SidebarLayout;
import com.osrstcg.ui.overview.OverviewTab;
import com.osrstcg.ui.shop.BoosterShopRow;
import com.osrstcg.ui.shop.ShopTab;
import com.osrstcg.ui.welcome.WelcomeContent;
import com.osrstcg.ui.welcome.WelcomeTab;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Slf4j
@Singleton
public class TcgPanel extends PluginPanel implements SidebarRefresh
{
	private static final int MAIN_PANEL_INSET = SidebarLayout.MAIN_PANEL_INSET;

	private enum Tab
	{
		WELCOME("Welcome"),
		OVERVIEW("Overview"),
		COLLECTION("Collection"),
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
	private final PackRevealService packRevealService;
	private final Client client;
	private final CloudSessionService cloudSessionService;
	private final TradeCloudService tradeCloudService;
	private final JButton openAccountPanelButton;
	private final JButton createProfileButton;
	private final JTextPane createProfilePromptPane;

	private final JPanel mainPanel = new JPanel();
	private final JPanel content = new JPanel();
	private final CardLayout contentLayout = new CardLayout();
	private final JPanel welcomeContent = new JPanel();
	private final JPanel overviewContent = new JPanel();
	private final JPanel collectionContent = new JPanel(new BorderLayout(0, 6));
	private final JPanel collectionListHost = new JPanel(new CardLayout());
	private final JList<CollectionListModel.Row> collectionList = new JList<>();
	private final JScrollPane collectionListScrollPane = new JScrollPane(collectionList);
	private final JLabel collectionEmptyLabel = new JLabel("No owned cards match these filters.");
	private final JPanel shopContent = new JPanel(new BorderLayout(0, 8));
	private final JPanel shopHeaderPanel = new JPanel();
	private final JPanel packsContent = new JPanel();
	private final JScrollPane welcomeScrollPane = new JScrollPane(welcomeContent);
	private final JScrollPane overviewScrollPane = new JScrollPane(overviewContent);
	private final JScrollPane shopPacksScrollPane = new JScrollPane(packsContent);
	private final JPanel footerPanel = new JPanel();
	private final JPanel createProfileFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final Component createProfileFooterSpacer = Box.createRigidArea(new Dimension(0, 10));
	private final JPanel albumFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final JPanel tradeFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final Component tradeFooterSpacer = Box.createRigidArea(new Dimension(0, 10));
	private final JPanel titlePanel;
	private JPanel titleTabWrapper;
	private final JComponent cloudStatusIndicator;
	private final JButton openTradesButton;
	private final JButton welcomeTabButton = new JButton(Tab.WELCOME.getLabel());
	private final JButton overviewTabButton = new JButton(Tab.OVERVIEW.getLabel());
	private final JButton collectionTabButton = new JButton(Tab.COLLECTION.getLabel());
	private final JButton shopTabButton = new JButton(Tab.SHOP.getLabel());
	private Tab selectedTab = Tab.OVERVIEW;
	private final Runnable onCollectionChanged = () -> SwingUtilities.invokeLater(this::refresh);
	private boolean defaultTabSelectionInitialized;
	private boolean refreshQueued;
	private boolean creditsRefreshQueued;
	private volatile boolean panelVisible;
	private int lastPanelWidthForLayout = -1;
	private int lastPanelHeightForLayout = -1;
	private final AtomicLong packCloseRefreshGen = new AtomicLong();
	private PackCloseSnapshot sidebarRevealSpoilerFreeze;
	private boolean welcomeBuiltForActiveReveal;
	private boolean overviewBuiltForActiveReveal;
	private boolean collectionBuiltForActiveReveal;
	private boolean shopBuiltForActiveReveal;

	private final WelcomeTab welcomeTab;
	private final OverviewTab overviewTab;
	private final CollectionTab collectionTab;
	private final ShopTab shopTab;
	private final CreateProfileController createProfileController;
	private final AccountPanelLauncher accountLauncher;
	private final SidebarNoticeView sidebarNoticeView;

	@Inject
	public TcgPanel(
		TcgStateService stateService,
		CardDatabase cardDatabase,
		WelcomeContent welcomeContentCatalog,
		CloudPackService cloudPackService,
		PackRevealService packRevealService,
		PackOpenCoordinator packOpenCoordinator,
		PackCatalogService packCatalogService,
		CardImageCacheService imageCacheService,
		OsrsTcgConfig config,
		Client client,
		CloudSessionService cloudSessionService,
		TradeCloudService tradeCloudService,
		CloudApiClient cloudApiClient,
		CloudSellService cloudSellService,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager)
	{
		super(false);
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.packRevealService = packRevealService;
		this.client = client;
		this.cloudSessionService = cloudSessionService;
		this.tradeCloudService = tradeCloudService;
		this.openAccountPanelButton = new JButton("Open web album");
		this.createProfileButton = new JButton("Create profile");
		this.createProfilePromptPane = CreateProfileController.createPromptPane();
		this.cloudStatusIndicator = SidebarChrome.createCloudStatusIndicator();
		this.openTradesButton = createOpenTradesButton();
		this.welcomeTab = new WelcomeTab(welcomeContentCatalog);
		this.overviewTab = new OverviewTab(
			config, cloudPackService, this::liveSidebarContentWidth, TcgPanel.class);
		this.accountLauncher = new AccountPanelLauncher(
			cloudSessionService, cloudApiClient, scheduler, chatMessageManager,
			this::updateManageAccountButtonState);
		this.openAccountPanelButton.addActionListener(e -> accountLauncher.open());
		this.createProfileController = new CreateProfileController(
			cloudSessionService, scheduler, chatMessageManager,
			this, this::refresh, this::selectOverviewAfterCreateProfile,
			accountLauncher::open, this::afterCreateProfileUi);
		this.createProfileButton.addActionListener(e -> createProfileController.createProfile());
		this.sidebarNoticeView = new SidebarNoticeView(
			openAccountPanelButton, albumFooterWrap, cloudSessionService, this::updateManageAccountButtonState);
		this.collectionTab = new CollectionTab(
			cardDatabase, packCatalogService, scheduler,
			this::liveSidebarContentWidth, this::capturePackCloseSnapshotForDisplay,
			this::onCollectionTabRendered, () -> selectedTab == Tab.COLLECTION,
			collectionContent, collectionListHost, collectionList, collectionListScrollPane, collectionEmptyLabel);
		this.shopTab = new ShopTab(
			stateService, cardDatabase, packRevealService,
			packOpenCoordinator, packCatalogService, imageCacheService, config, cloudSessionService,
			cloudSellService, scheduler, chatMessageManager, overviewTab,
			this::liveShopPacksContentWidth, this::capturePackCloseSnapshotForDisplay,
			this::refresh, this::beginPackRevealSidebarFreeze, this::clearPackRevealSidebarFreeze,
			this, shopHeaderPanel, packsContent);

		setLayout(new BorderLayout());

		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setLayout(new BorderLayout(0, 8));
		mainPanel.setBorder(new EmptyBorder(
			MAIN_PANEL_INSET, MAIN_PANEL_INSET, MAIN_PANEL_INSET, MAIN_PANEL_INSET));

		content.setLayout(contentLayout);
		content.setOpaque(false);
		welcomeContent.setLayout(new BorderLayout());
		welcomeContent.setOpaque(false);
		SidebarLayout.initializeTabContentPanel(overviewContent);
		SidebarLayout.initializeTabContentPanel(packsContent);
		collectionContent.setOpaque(false);
		collectionTab.configureList();
		SidebarLayout.configureTabScrollPane(collectionListScrollPane);
		collectionListHost.setOpaque(false);
		collectionListHost.add(collectionListScrollPane, CollectionTab.LIST_CARD);
		JPanel emptyWrap = new JPanel(new BorderLayout());
		emptyWrap.setOpaque(false);
		emptyWrap.add(collectionEmptyLabel, BorderLayout.NORTH);
		collectionListHost.add(emptyWrap, CollectionTab.EMPTY_CARD);
		collectionContent.add(collectionListHost, BorderLayout.CENTER);
		shopContent.setOpaque(false);
		shopHeaderPanel.setLayout(new BoxLayout(shopHeaderPanel, BoxLayout.Y_AXIS));
		shopHeaderPanel.setOpaque(false);
		shopHeaderPanel.setAlignmentX(LEFT_ALIGNMENT);
		shopContent.add(shopHeaderPanel, BorderLayout.NORTH);
		shopContent.add(shopPacksScrollPane, BorderLayout.CENTER);
		content.add(welcomeScrollPane, Tab.WELCOME.name());
		content.add(overviewScrollPane, Tab.OVERVIEW.name());
		content.add(collectionContent, Tab.COLLECTION.name());
		content.add(shopContent, Tab.SHOP.name());
		content.add(sidebarNoticeView.content(), SidebarNoticeView.CARD);

		SidebarLayout.configureTabScrollPane(welcomeScrollPane);
		SidebarLayout.configureTabScrollPane(overviewScrollPane);
		SidebarLayout.configureTabScrollPane(shopPacksScrollPane);
		shopPacksScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

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
				int nh = getHeight();
				boolean widthChanged = nw > 0 && nw != lastPanelWidthForLayout;
				boolean heightChanged = nh > 0 && nh != lastPanelHeightForLayout;
				if (!widthChanged && !heightChanged)
				{
					return;
				}
				lastPanelWidthForLayout = nw;
				lastPanelHeightForLayout = nh;
				if (widthChanged)
				{
					refresh();
				}
				else
				{
					revalidate();
					repaint();
				}
			}
		});

		panelVisible = isShowing();
	}

	@Override
	public Dimension getPreferredSize()
	{
		Dimension pref = super.getPreferredSize();
		Container parent = getParent();
		int height = pref.height;
		if (parent != null)
		{
			height = Math.max(height, parent.getHeight());
		}
		return new Dimension(pref.width, height);
	}

	@Override
	public Dimension getMinimumSize()
	{
		Dimension pref = getPreferredSize();
		return new Dimension(pref.width, 0);
	}

	@Override
	public Dimension getMaximumSize()
	{
		Dimension pref = getPreferredSize();
		return new Dimension(pref.width, Integer.MAX_VALUE);
	}

	public void start()
	{
		stateService.addCollectionChangeListener(onCollectionChanged);
		updateCloudStatusIndicator();
		refresh();
	}

	public void stop()
	{
		stateService.removeCollectionChangeListener(onCollectionChanged);
		collectionTab.cancelPendingRebuilds();
		welcomeContent.removeAll();
		overviewContent.removeAll();
		collectionContent.removeAll();
		collectionTab.clearList();
		shopTab.clear();
		mainPanel.revalidate();
		mainPanel.repaint();
	}

	@Override
	public void refresh()
	{
		if (!panelVisible)
		{
			return;
		}

		if (!SwingUtilities.isEventDispatchThread())
		{
			queueRefreshOnEdt();
			return;
		}

		refreshNow();
	}

	@Override
	public void refreshCredits()
	{
		if (!panelVisible)
		{
			return;
		}
		if (sidebarRevealSpoilerFreeze != null && packRevealService.isActive())
		{
			return;
		}
		if (!SwingUtilities.isEventDispatchThread())
		{
			if (creditsRefreshQueued || refreshQueued)
			{
				return;
			}
			creditsRefreshQueued = true;
			SwingUtilities.invokeLater(() ->
			{
				creditsRefreshQueued = false;
				refreshCredits();
			});
			return;
		}
		long credits = stateService.getCredits();
		overviewTab.updateCredits(credits);
		shopTab.updateCredits(credits);
	}

	@Override
	public void refreshAfterPackRevealClose()
	{
		if (!panelVisible)
		{
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
				CloudSidebarCollectionStats metrics = TcgPublicStatsCalculator.resolveOverview(snap, all, rollPool);
				List<BoosterShopRow> shopRows = shopTab.computeRows(snap);
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

	private void applyPackCloseRefresh(long gen, PackCloseSnapshot snap, CloudSidebarCollectionStats metrics, List<BoosterShopRow> shopRows)
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
			return;
		}
		if (applyNormalSidebarChromeOrBlock())
		{
			return;
		}
		if (selectedTab == Tab.OVERVIEW)
		{
			overviewContent.removeAll();
			overviewTab.render(overviewContent, snap, metrics);
			contentLayout.show(content, Tab.OVERVIEW.name());
		}
		else if (selectedTab == Tab.COLLECTION)
		{
			collectionTab.render();
			contentLayout.show(content, Tab.COLLECTION.name());
		}
		else if (selectedTab == Tab.SHOP)
		{
			shopTab.renderFromPackClose(snap, shopRows);
			showTabContent(Tab.SHOP);
		}
		else if (selectedTab == Tab.WELCOME)
		{
			welcomeContent.removeAll();
			renderWelcomeTab(welcomeContent);
			contentLayout.show(content, Tab.WELCOME.name());
		}
		mainPanel.revalidate();
		mainPanel.repaint();
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
		updateCloudStatusIndicator();
		if (applyNormalSidebarChromeOrBlock())
		{
			return;
		}
		renderSelectedTab();
		mainPanel.revalidate();
		mainPanel.repaint();
	}

	private boolean applyNormalSidebarChromeOrBlock()
	{
		if (shouldShowLoggedOutPrompt())
		{
			showLoggedOutWelcome();
			mainPanel.revalidate();
			mainPanel.repaint();
			return true;
		}
		if (cloudSessionService.isAccountLocked())
		{
			showSidebarBlockingNotice(sidebarNoticeView::showAccountLockedNotice);
			mainPanel.revalidate();
			mainPanel.repaint();
			return true;
		}
		if (cloudSessionService.isRestrictedWorld())
		{
			showSidebarBlockingNotice(sidebarNoticeView::showEventWorldUnavailable);
			mainPanel.revalidate();
			mainPanel.repaint();
			return true;
		}
		titleTabWrapper.setVisible(true);
		footerPanel.setVisible(true);
		sidebarNoticeView.restoreOpenAccountPanelButtonToFooter();
		applyDefaultTabSelectionOnce();
		updateTabStyles();
		return false;
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

		createProfileFooterWrap.setOpaque(false);
		createProfileFooterWrap.setLayout(new BorderLayout(0, 8));
		createProfileFooterWrap.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		SidebarLayout.stylePrimaryFooterButton(createProfileButton);
		createProfileFooterWrap.add(createProfilePromptPane, BorderLayout.NORTH);
		createProfileFooterWrap.add(createProfileButton, BorderLayout.SOUTH);
		footerPanel.add(createProfileFooterWrap);

		footerPanel.add(createProfileFooterSpacer);

		tradeFooterWrap.setOpaque(false);
		SidebarLayout.stylePrimaryFooterButton(openTradesButton);
		tradeFooterWrap.add(openTradesButton, BorderLayout.CENTER);
		SidebarLayout.clampPanelWidth(tradeFooterWrap);
		footerPanel.add(tradeFooterWrap);

		footerPanel.add(tradeFooterSpacer);

		albumFooterWrap.setOpaque(false);
		SidebarLayout.stylePrimaryFooterButton(openAccountPanelButton);
		albumFooterWrap.add(openAccountPanelButton, BorderLayout.CENTER);
		SidebarLayout.clampPanelWidth(albumFooterWrap);
		footerPanel.add(albumFooterWrap);

		updateFooterVisibility();
	}

	private boolean shouldShowLoggedOutPrompt()
	{
		if (!isShowing())
		{
			return false;
		}
		return !isClientInGameWorld();
	}

	private void showLoggedOutWelcome()
	{
		sidebarNoticeView.restoreOpenAccountPanelButtonToFooter();
		titleTabWrapper.setVisible(true);
		selectedTab = Tab.WELCOME;
		updateTabStyles();
		welcomeContent.removeAll();
		renderWelcomeTab(welcomeContent);
		contentLayout.show(content, Tab.WELCOME.name());
	}

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

		JPanel titleRow = new JPanel(new BorderLayout(0, 0));
		titleRow.setOpaque(false);
		titleRow.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(0, 8, 2, 8)
		));
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

		Dimension indicatorSlot = new Dimension(8, 8);

		JPanel leftLinks = new JPanel();
		leftLinks.setLayout(new BoxLayout(leftLinks, BoxLayout.X_AXIS));
		leftLinks.setOpaque(false);
		leftLinks.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		JComponent discordLink = SidebarLayout.createTitleLinkButton("/com/osrstcg/images/discord.png", "Join our Discord", SidebarLayout.DISCORD_URL);
		JComponent patreonLink = SidebarLayout.createTitleLinkButton("/com/osrstcg/images/patreon.png", "Support on Patreon", SidebarLayout.PATREON_URL);
		if (discordLink != null)
		{
			leftLinks.add(discordLink);
		}
		if (discordLink != null && patreonLink != null)
		{
			leftLinks.add(Box.createRigidArea(new Dimension(6, 0)));
		}
		if (patreonLink != null)
		{
			leftLinks.add(patreonLink);
		}

		JLabel titleLabel = new JLabel("OSRS TCG");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);

		cloudStatusIndicator.setPreferredSize(indicatorSlot);
		cloudStatusIndicator.setMinimumSize(indicatorSlot);
		cloudStatusIndicator.setMaximumSize(indicatorSlot);

		int sideW = Math.max(leftLinks.getPreferredSize().width, indicatorSlot.width);
		int sideH = Math.max(16, Math.max(leftLinks.getPreferredSize().height, indicatorSlot.height));
		Dimension sideSlot = new Dimension(sideW, sideH);

		JPanel leftSlot = new JPanel(new BorderLayout(0, 0));
		leftSlot.setOpaque(false);
		leftSlot.setPreferredSize(sideSlot);
		leftSlot.setMinimumSize(sideSlot);
		leftSlot.setMaximumSize(sideSlot);
		leftSlot.add(leftLinks, BorderLayout.WEST);

		JPanel rightSlot = new JPanel(new BorderLayout(0, 0));
		rightSlot.setOpaque(false);
		rightSlot.setPreferredSize(sideSlot);
		rightSlot.setMinimumSize(sideSlot);
		rightSlot.setMaximumSize(sideSlot);
		JPanel indicatorWrap = new JPanel(new BorderLayout(0, 0));
		indicatorWrap.setOpaque(false);
		indicatorWrap.setBorder(new EmptyBorder(0, 0, 2, 0));
		indicatorWrap.add(cloudStatusIndicator, BorderLayout.EAST);
		rightSlot.add(indicatorWrap, BorderLayout.EAST);

		titleRow.add(leftSlot, BorderLayout.WEST);
		titleRow.add(titleLabel, BorderLayout.CENTER);
		titleRow.add(rightSlot, BorderLayout.EAST);

		JPanel tabStrip = new JPanel(new BorderLayout(0, 0))
		{
			@Override
			protected void paintChildren(Graphics g)
			{
				super.paintChildren(g);
				paintTabRailLine(this, g);
			}
		};
		tabStrip.setOpaque(true);
		tabStrip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tabStrip.setBorder(new EmptyBorder(4, -MAIN_PANEL_INSET, 0, -MAIN_PANEL_INSET));

		JPanel tabButtons = new JPanel(new GridLayout(1, 4, SidebarLayout.TAB_BUTTON_GAP, 0));
		tabButtons.setOpaque(false);
		tabButtons.add(configureTabButton(welcomeTabButton, Tab.WELCOME));
		tabButtons.add(configureTabButton(overviewTabButton, Tab.OVERVIEW));
		tabButtons.add(configureTabButton(collectionTabButton, Tab.COLLECTION));
		tabButtons.add(configureTabButton(shopTabButton, Tab.SHOP));

		JComponent leftWing = SidebarLayout.tabRailWing();
		JComponent rightWing = SidebarLayout.tabRailWing();
		tabStrip.add(leftWing, BorderLayout.WEST);
		tabStrip.add(tabButtons, BorderLayout.CENTER);
		tabStrip.add(rightWing, BorderLayout.EAST);

		title.add(titleRow);
		title.add(tabStrip);
		titleTabWrapper = tabStrip;
		updateCloudStatusIndicator();
		updateTabStyles();
		return title;
	}

	private void paintTabRailLine(JComponent strip, Graphics g)
	{
		JButton active = tabButtonFor(selectedTab);
		if (active != null && (!active.isShowing() || !isTabAvailable(selectedTab)))
		{
			active = null;
		}
		SidebarChrome.paintTabRailLine(strip, g, active);
	}

	private JButton tabButtonFor(Tab tab)
	{
		if (tab == null)
		{
			return null;
		}
		switch (tab)
		{
			case WELCOME:
				return welcomeTabButton;
			case OVERVIEW:
				return overviewTabButton;
			case COLLECTION:
				return collectionTabButton;
			case SHOP:
				return shopTabButton;
			default:
				return null;
		}
	}

	public void updateCloudStatusIndicator()
	{
		SidebarChrome.updateCloudStatusIndicator(cloudStatusIndicator, cloudSessionService, stateService);

		Container parent = cloudStatusIndicator.getParent();
		if (parent != null)
		{
			parent.revalidate();
			parent.repaint();
		}
		Container titleRow = parent == null ? null : parent.getParent();
		if (titleRow != null)
		{
			titleRow.revalidate();
			titleRow.repaint();
		}
		cloudStatusIndicator.revalidate();
		cloudStatusIndicator.repaint();
		updateManageAccountButtonState();
		updateFooterVisibility();
	}

	private int footerContentWidth()
	{
		int footerW = footerPanel.getWidth();
		if (footerW > 0)
		{
			return footerW;
		}
		int panelW = getWidth();
		if (panelW > 0)
		{
			return Math.max(80, panelW - 12);
		}
		return Math.max(80, PluginPanel.PANEL_WIDTH - 12);
	}

	private JButton configureTabButton(JButton button, Tab tab)
	{
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusable(false);
		button.setFocusPainted(false);
		button.setBorderPainted(true);
		button.setHorizontalAlignment(SwingConstants.CENTER);
		button.addActionListener(e ->
		{
			if (!isTabAvailable(tab) || selectedTab == tab)
			{
				return;
			}
			selectedTab = tab;
			updateTabStyles();
			refresh();
		});
		return button;
	}

	private void updateTabStyles()
	{
		if (!isTabAvailable(selectedTab))
		{
			selectedTab = Tab.WELCOME;
		}
		applyTabStyle(welcomeTabButton, Tab.WELCOME);
		applyTabStyle(overviewTabButton, Tab.OVERVIEW);
		applyTabStyle(collectionTabButton, Tab.COLLECTION);
		applyTabStyle(shopTabButton, Tab.SHOP);
		if (titleTabWrapper != null)
		{
			titleTabWrapper.revalidate();
			titleTabWrapper.repaint();
		}
		updateFooterVisibility();
	}

	private boolean isTabAvailable(Tab tab)
	{
		if (tab == null)
		{
			return false;
		}
		if (tab == Tab.WELCOME)
		{
			return true;
		}
		return isClientInGameWorld()
			&& !cloudSessionService.isRestrictedWorld()
			&& !cloudSessionService.isAccountLocked();
	}

	private void updateFooterVisibility()
	{
		if (cloudSessionService.isAccountLocked() && isClientInGameWorld())
		{
			footerPanel.setVisible(false);
			return;
		}
		if (cloudSessionService.isRestrictedWorld() && isClientInGameWorld())
		{
			footerPanel.setVisible(false);
			return;
		}
		boolean inWorld = isClientInGameWorld();
		boolean restrictedWorld = cloudSessionService.isRestrictedWorld();
		boolean showCreateProfile = inWorld && !restrictedWorld
			&& cloudSessionService.needsProfileCreate();

		footerPanel.setVisible(true);
		sidebarNoticeView.restoreOpenAccountPanelButtonToFooter();
		createProfileFooterWrap.setVisible(showCreateProfile);
		updateCreateProfileButtonState();

		boolean cloudConnected = cloudSessionService.isSessionActive()
			&& !cloudSessionService.needsCloudConsent();
		boolean showAccountPanel = inWorld && !restrictedWorld && cloudConnected;
		albumFooterWrap.setVisible(showAccountPanel);
		updateManageAccountButtonState();

		boolean showTrade = inWorld
			&& !restrictedWorld
			&& cloudConnected
			&& tradeCloudService.getPendingAccept() != null
			&& selectedTab != Tab.WELCOME;
		tradeFooterWrap.setVisible(showTrade);

		createProfileFooterSpacer.setVisible(showCreateProfile && (showAccountPanel || showTrade));
		tradeFooterSpacer.setVisible(showTrade && showAccountPanel);

		if (showCreateProfile)
		{
			createProfileController.updatePromptLayout(
				createProfilePromptPane, createProfileFooterWrap, footerContentWidth());
		}
		SidebarLayout.lockFooterBlockHeight(albumFooterWrap);
		SidebarLayout.lockFooterBlockHeight(tradeFooterWrap);
	}

	private JButton createOpenTradesButton()
	{
		JButton button = new JButton(
			"<html><center>Open trades<br>"
				+ "<span style='font-family:SansSerif;font-size:8px;color:#aaaaaa'>You have pending trades waiting...</span>"
				+ "</center></html>");
		button.addActionListener(e -> accountLauncher.open("/trades"));
		return button;
	}

	private void applyTabStyle(JButton button, Tab tab)
	{
		boolean available = isTabAvailable(tab);
		boolean active = available && selectedTab == tab;
		button.setEnabled(available);
		if (!available)
		{
			Color muted = new Color(0x666666);
			button.setForeground(muted);
			button.setOpaque(false);
			button.setContentAreaFilled(false);
			button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			button.setBorder(tabBorder(false, false));
			button.setCursor(Cursor.getDefaultCursor());
			button.setToolTipText(
				(tab == Tab.OVERVIEW || tab == Tab.COLLECTION || tab == Tab.SHOP)
					? "Log in to RuneScape to use this tab"
					: null);
			return;
		}
		button.setToolTipText(null);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		if (active)
		{
			button.setForeground(ColorScheme.BRAND_ORANGE);
			button.setOpaque(true);
			button.setContentAreaFilled(true);
			button.setBackground(ColorScheme.DARK_GRAY_COLOR);
			button.setBorder(tabBorder(true, true));
		}
		else
		{
			button.setForeground(Color.WHITE);
			button.setOpaque(false);
			button.setContentAreaFilled(false);
			button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			button.setBorder(tabBorder(false, true));
		}
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
			if (selectedTab == Tab.COLLECTION)
			{
				if (!collectionBuiltForActiveReveal)
				{
					collectionTab.render();
					collectionBuiltForActiveReveal = true;
				}
				showTabContent(Tab.COLLECTION);
				return;
			}
			if (selectedTab == Tab.SHOP)
			{
				if (!shopBuiltForActiveReveal)
				{
					shopTab.render();
					shopBuiltForActiveReveal = true;
				}
				showTabContent(Tab.SHOP);
				return;
			}
			log.warn("Unsupported tab {}", selectedTab);
			contentLayout.show(content, selectedTab.name());
			return;
		}

		if (selectedTab == Tab.COLLECTION)
		{
			collectionTab.render();
			showTabContent(selectedTab);
			return;
		}

		if (selectedTab == Tab.SHOP)
		{
			shopTab.render();
			showTabContent(selectedTab);
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
			default:
				log.warn("Unsupported tab {}", selectedTab);
		}
		showTabContent(selectedTab);
	}

	private void showTabContent(Tab tab)
	{
		contentLayout.show(content, tab.name());
		if (tab == Tab.WELCOME)
		{
			SidebarLayout.revalidateTabScrollPane(welcomeScrollPane);
		}
		else if (tab == Tab.OVERVIEW)
		{
			SidebarLayout.revalidateTabScrollPane(overviewScrollPane);
		}
		else if (tab == Tab.COLLECTION)
		{
			SidebarLayout.revalidateTabScrollPane(collectionListScrollPane);
		}
		else if (tab == Tab.SHOP)
		{
			SidebarLayout.revalidateTabScrollPane(shopPacksScrollPane);
			shopHeaderPanel.revalidate();
			shopContent.revalidate();
		}
	}

	private JPanel panelForTab(Tab tab)
	{
		switch (tab)
		{
			case WELCOME:
				return welcomeContent;
			case OVERVIEW:
				return overviewContent;
			case COLLECTION:
				return collectionContent;
			case SHOP:
				return packsContent;
			default:
				return overviewContent;
		}
	}

	private PackCloseSnapshot capturePackCloseSnapshot()
	{
		synchronized (stateService)
		{
			TcgState s = stateService.getState();
			CollectionState collection = s.getCollectionState();
			return new PackCloseSnapshot(
				new HashMap<>(collection.getOwnedCardsExcludingBeta()),
				collection,
				stateService.getCredits(),
				s.getEconomyState().getOpenedPacks(),
				stateService.getCloudCollectionStats());
		}
	}

	private PackCloseSnapshot capturePackCloseSnapshotForDisplay()
	{
		if (sidebarRevealSpoilerFreeze != null && packRevealService.isActive())
		{
			return sidebarRevealSpoilerFreeze;
		}
		return capturePackCloseSnapshot();
	}

	@Override
	public void beginPackRevealSidebarFreeze()
	{
		sidebarRevealSpoilerFreeze = capturePackCloseSnapshot();
		welcomeBuiltForActiveReveal = false;
		overviewBuiltForActiveReveal = false;
		collectionBuiltForActiveReveal = false;
		shopBuiltForActiveReveal = false;
	}

	@Override
	public void clearPackRevealSidebarFreeze()
	{
		sidebarRevealSpoilerFreeze = null;
		welcomeBuiltForActiveReveal = false;
		overviewBuiltForActiveReveal = false;
		collectionBuiltForActiveReveal = false;
		shopBuiltForActiveReveal = false;
	}

	private int liveSidebarContentWidth()
	{
		int viewportWidth = Math.max(
			Math.max(
				Math.max(welcomeScrollPane.getViewport().getWidth(), overviewScrollPane.getViewport().getWidth()),
				collectionListScrollPane.getViewport().getWidth()),
			shopPacksScrollPane.getViewport().getWidth());
		if (viewportWidth > 0)
		{
			return Math.max(80, viewportWidth);
		}

		Insets pi = getInsets();
		int raw = getWidth() - pi.left - pi.right;
		if (raw <= 0)
		{
			return SidebarLayout.sidebarInnerWidth();
		}
		int mainPanelHorizontalPad = 12;
		return Math.max(80, raw - mainPanelHorizontalPad - SidebarLayout.TAB_SCROLLBAR_RESERVED_WIDTH);
	}

	private int liveShopPacksContentWidth()
	{
		int viewportWidth = shopPacksScrollPane.getViewport().getWidth();
		if (viewportWidth > 0)
		{
			return Math.max(80, viewportWidth);
		}
		return liveSidebarContentWidth();
	}

	private javax.swing.border.Border tabBorder(boolean active, boolean enabled)
	{
		if (!enabled)
		{
			return new CompoundBorder(
				new MatteBorder(1, 1, 1, 1, ColorScheme.DARKER_GRAY_COLOR.brighter()),
				new EmptyBorder(5, 2, 5, 2)
			);
		}
		if (active)
		{
			return new CompoundBorder(
				new MatteBorder(1, 1, 0, 1, ColorScheme.MEDIUM_GRAY_COLOR),
				new EmptyBorder(5, 2, 6, 2)
			);
		}
		return new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(5, 2, 5, 2)
		);
	}

	private void renderWelcomeTab(JPanel target)
	{
		welcomeTab.render(target, liveSidebarContentWidth());
	}

	private void renderOverviewTab(JPanel target)
	{
		PackCloseSnapshot snap = capturePackCloseSnapshotForDisplay();
		List<CardDefinition> all = cardDatabase.getCards();
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(all);
		CloudSidebarCollectionStats metrics = TcgPublicStatsCalculator.resolveOverview(snap, all, rollPool);
		overviewTab.render(target, snap, metrics);
	}

	private void onCollectionTabRendered()
	{
		showTabContent(Tab.COLLECTION);
		mainPanel.revalidate();
		mainPanel.repaint();
	}

	private void showSidebarBlockingNotice(Consumer<Runnable> show)
	{
		show.accept(() ->
		{
			titleTabWrapper.setVisible(false);
			footerPanel.setVisible(false);
		});
		contentLayout.show(content, SidebarNoticeView.CARD);
		titlePanel.revalidate();
		titlePanel.repaint();
	}

	private void updateManageAccountButtonState()
	{
		accountLauncher.updateManageAccountButtonState(openAccountPanelButton, openTradesButton);
	}

	private void updateCreateProfileButtonState()
	{
		createProfileController.updateButtonState(createProfileButton);
	}

	private void selectOverviewAfterCreateProfile()
	{
		if (selectedTab != Tab.OVERVIEW)
		{
			selectedTab = Tab.OVERVIEW;
			updateTabStyles();
		}
	}

	private void afterCreateProfileUi()
	{
		updateCreateProfileButtonState();
		updateFooterVisibility();
		updateCloudStatusIndicator();
	}
}

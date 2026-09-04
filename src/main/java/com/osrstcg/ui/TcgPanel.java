package com.osrstcg.ui;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.session.CloudSessionService;
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
import java.util.Arrays;
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
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
/**
 * RuneLite sidebar panel hosting the plugin's tabs (welcome, overview, collection, shop) plus the
 * title bar, tab strip, and footer (account/trade/create-profile actions). Owns the Swing component
 * tree for the sidebar and delegates per-tab content to {@link WelcomeTab}, {@link OverviewTab},
 * {@link CollectionTab}, and {@link ShopTab}. Implements {@link SidebarRefresh} so other components
 * (pack open flow, account/create-profile controllers) can trigger refreshes without depending on this
 * class directly.
 *
 * <p>All Swing mutation must happen on the EDT. Public refresh methods accept calls from any thread and
 * hop to the EDT via {@link SwingUtilities#invokeLater} when needed; most private helpers assume they
 * are already running on the EDT.
 */
@Slf4j
@Singleton
public class TcgPanel extends PluginPanel implements SidebarRefresh
{
	private static final int MAIN_PANEL_INSET = SidebarLayout.MAIN_PANEL_INSET;
/** The four sidebar tabs, in display order, each carrying its button label. */
	private enum Tab
	{
		WELCOME("Welcome"),
		OVERVIEW("Overview"),
		COLLECTION("Collection"),
		SHOP("Shop");

		final String label;

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
	private final Component createProfileFooterGap = Box.createRigidArea(new Dimension(0, 10));
	private final JPanel albumFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final JPanel tradeFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final Component tradeFooterSpacer = Box.createRigidArea(new Dimension(0, 10));
	private final JPanel titlePanel;
	private JPanel titleTabWrapper;
	private final JComponent cloudStatusIndicator;
	private final JButton openTradesButton;
	private final JButton welcomeTabButton = new JButton(Tab.WELCOME.label);
	private final JButton overviewTabButton = new JButton(Tab.OVERVIEW.label);
	private final JButton collectionTabButton = new JButton(Tab.COLLECTION.label);
	private final JButton shopTabButton = new JButton(Tab.SHOP.label);
	private Tab selectedTab = Tab.OVERVIEW;
	private final Runnable onCollectionChanged = () -> SwingUtilities.invokeLater(this::refresh);
	private boolean defaultTabInitialized;
	private boolean refreshQueued;
	private boolean creditsRefreshQueued;
	private volatile boolean panelVisible;
	private int lastPanelWidthForLayout = -1;
	private int lastPanelHeightForLayout = -1;
	private final AtomicLong packCloseRefreshGen = new AtomicLong();
	private PackCloseSnapshot sidebarRevealSpoilerSnap;
	private final boolean[] revealTabBuilt = new boolean[Tab.values().length];

	private final WelcomeTab welcomeTab;
	private final OverviewTab overviewTab;
	private final CollectionTab collectionTab;
	private final ShopTab shopTab;
	private final CreateProfileController createProfileController;
	private final AccountPanelLauncher accountLauncher;
	private final SidebarNoticeView sidebarNoticeView;
/** Wires collaborators, builds the full Swing component tree (title, tabs, footer), and installs the resize/visibility listener. */
	@Inject
	public TcgPanel(
		TcgStateService stateService,
		CardDatabase cardDatabase,
		WelcomeContent welcomeContentCatalog,
		PackRevealService packRevealService,
		PackOpenCoordinator packOpenCoordinator,
		PackCatalogService packCatalogService,
		CardImageCacheService imageCacheService,
		OsrsTcgConfig config,
		Client client,
		CloudSessionService cloudSessionService,
		TradeCloudService tradeCloudService,
		CloudApiClient cloudApiClient,
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
			config, stateService, this::liveSidebarContentWidth, TcgPanel.class);
		this.accountLauncher = new AccountPanelLauncher(
			cloudSessionService, cloudApiClient, scheduler, chatMessageManager,
			this::updateManageAccountState);
		this.openAccountPanelButton.addActionListener(e -> accountLauncher.open("/me"));
		this.createProfileController = new CreateProfileController(
			cloudSessionService, scheduler, chatMessageManager,
			this, this::refresh, this::selectOverviewAfterCreate,
			() -> accountLauncher.open("/me"), this::afterCreateProfileUi);
		this.createProfileButton.addActionListener(e -> createProfileController.createProfile());
		this.sidebarNoticeView = new SidebarNoticeView(
			openAccountPanelButton, albumFooterWrap, cloudSessionService, this::updateManageAccountState);
		this.collectionTab = new CollectionTab(
			cardDatabase, packCatalogService, scheduler,
			this::liveSidebarContentWidth, this::capturePackCloseSnapshot,
			this::onCollectionTabRendered, () -> selectedTab == Tab.COLLECTION,
			collectionContent, collectionListHost, collectionList, collectionListScrollPane, collectionEmptyLabel);
		this.shopTab = new ShopTab(
			stateService, cardDatabase, packRevealService,
			packOpenCoordinator, packCatalogService, imageCacheService, cloudSessionService,
			overviewTab,
			this::liveShopPacksContentWidth, this::capturePackCloseSnapshot,
			this::refresh, this::beginPackRevealSidebarFreeze, this::clearPackRevealSidebarFreeze,
			shopHeaderPanel, packsContent);

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
/** Marks the panel visible and forces a refresh whenever RuneLite shows the sidebar. */
			@Override
			public void componentShown(ComponentEvent e)
			{
				panelVisible = true;
				refresh();
			}
/** Marks the panel hidden so refresh calls become no-ops until it's shown again. */
			@Override
			public void componentHidden(ComponentEvent e)
			{
				panelVisible = false;
			}
/** Re-renders on a width change (content reflows), or just revalidates/repaints on a height-only change. */
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
/** Preferred size stretched to at least the parent's height so the panel fills the sidebar vertically. */
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
/** Minimum height of 0 so the panel can shrink freely; width matches the preferred width. */
	@Override
	public Dimension getMinimumSize()
	{
		Dimension pref = getPreferredSize();
		return new Dimension(pref.width, 0);
	}
/** Unbounded maximum height so the panel can grow to fill available vertical space. */
	@Override
	public Dimension getMaximumSize()
	{
		Dimension pref = getPreferredSize();
		return new Dimension(pref.width, Integer.MAX_VALUE);
	}
/** Registers the collection-change listener and does an initial refresh. Called by the plugin on startup. */
	public void start()
	{
		stateService.addCollectionChangeListener(onCollectionChanged);
		updateCloudStatusIndicator();
		refresh();
	}
/** Unregisters listeners, cancels pending tab rebuilds, and clears tab content. Called by the plugin on shutdown. */
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
/**
	 * Rebuilds/redisplays the currently selected tab. No-op while the panel isn't visible. Safe to call
	 * from any thread; hops to the EDT (coalescing concurrent calls) when not already on it.
	 */
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
/**
	 * Refreshes just the displayed credits balance on the overview and shop tabs. Skipped while a pack
	 * reveal is showing its frozen snapshot. Safe to call from any thread; hops to the EDT (coalescing
	 * with a pending full refresh or credits refresh) when not already on it.
	 */
	@Override
	public void refreshCredits()
	{
		if (!panelVisible)
		{
			return;
		}
		if (sidebarRevealSpoilerSnap != null && packRevealService.isActive())
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
/**
	 * Refreshes the sidebar once a pack reveal overlay closes: clears the reveal freeze, then
	 * recomputes the overview/shop snapshot off the EDT (on the common {@link ForkJoinPool}) and applies
	 * it back on the EDT, guarded by a generation counter so a stale async result can't clobber a newer
	 * one. Falls back to a synchronous {@link #refresh()} if the async computation throws. Safe to call
	 * from any thread.
	 */
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
				CloudSidebarCollectionStats metrics = overviewMetrics(snap);
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
/**
	 * Applies a snapshot computed off-EDT by {@link #refreshAfterPackRevealClose()}: hops to the EDT if
	 * needed, discards the result if a newer generation has since started or the panel isn't visible,
	 * then renders the selected tab with the precomputed data.
	 */
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
		if (applySidebarChromeOrBlock())
		{
			return;
		}
		renderTab(selectedTab, TabRenderMode.PACK_CLOSE, snap, metrics, shopRows);
		relayoutMainPanel();
	}
/** Revalidates and repaints the main panel after content changes. Must be called on the EDT. */
	private void relayoutMainPanel()
	{
		mainPanel.revalidate();
		mainPanel.repaint();
	}
/** Schedules a {@link #refresh()} on the EDT, coalescing with any already-queued call. */
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
/** EDT-side body of {@link #refresh()}: clears any stale reveal freeze, updates chrome, and renders the selected tab. */
	private void refreshNow()
	{
		if (!packRevealService.isActive())
		{
			clearPackRevealSidebarFreeze();
		}
		updateCloudStatusIndicator();
		if (applySidebarChromeOrBlock())
		{
			return;
		}
		renderSelectedTab();
		relayoutMainPanel();
	}
/**
	 * Shows a full-panel blocking notice (logged out, account locked, restricted world) in place of tab
	 * content when applicable; otherwise restores normal title/tab/footer chrome. Returns whether a
	 * blocking notice was shown (and content rendering should be skipped).
	 */
	private boolean applySidebarChromeOrBlock()
	{
		if (shouldShowLoggedOutPrompt())
		{
			showLoggedOutWelcome();
		}
		else if (cloudSessionService.isAccountLocked())
		{
			showSidebarBlockingNotice(sidebarNoticeView::showAccountLockedNotice);
		}
		else if (cloudSessionService.isRestrictedWorld())
		{
			showSidebarBlockingNotice(sidebarNoticeView::showEventWorldUnavailable);
		}
		else
		{
			titleTabWrapper.setVisible(true);
			footerPanel.setVisible(true);
			sidebarNoticeView.restoreAccountPanelToFooter();
			applyDefaultTabSelectionOnce();
			updateTabStyles();
			return false;
		}
		relayoutMainPanel();
		return true;
	}
/** Picks the initial tab (Welcome if no packs opened yet, else Overview) the first time chrome is applied. */
	private void applyDefaultTabSelectionOnce()
	{
		if (defaultTabInitialized)
		{
			return;
		}
		defaultTabInitialized = true;
		long openedPacks = stateService.getState().getEconomyState().getOpenedPacks();
		selectedTab = openedPacks == 0 ? Tab.WELCOME : Tab.OVERVIEW;
	}
/** Builds the footer's layout and adds its three stacked blocks (create-profile prompt, trade button, account button). */
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

		footerPanel.add(createProfileFooterGap);

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
/** Returns whether to show the logged-out welcome screen: panel is showing but the client isn't in a game world. */
	private boolean shouldShowLoggedOutPrompt()
	{
		if (!isShowing())
		{
			return false;
		}
		return !isClientInGameWorld();
	}
/** Forces the Welcome tab and renders it, used when the player isn't logged into a game world. */
	private void showLoggedOutWelcome()
	{
		sidebarNoticeView.restoreAccountPanelToFooter();
		titleTabWrapper.setVisible(true);
		selectedTab = Tab.WELCOME;
		updateTabStyles();
		welcomeContent.removeAll();
		renderWelcomeTab(welcomeContent);
		contentLayout.show(content, Tab.WELCOME.name());
	}
/** Returns whether the RuneLite client is logged into a game world (checks game state, then local player as a fallback). */
	private boolean isClientInGameWorld()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			return true;
		}
		return client.getLocalPlayer() != null;
	}
/**
	 * Builds the title panel: the top title row (Discord/Patreon links, "OSRS TCG" label, cloud status
	 * indicator) and the tab strip below it. Sets {@link #titleTabWrapper} to the tab strip.
	 */
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
/** Paints the tab-rail underline beneath the active tab button, or no line if the active tab isn't showing/available. */
	private void paintTabRailLine(JComponent strip, Graphics g)
	{
		JButton active = tabButtonFor(selectedTab);
		if (active != null && (!active.isShowing() || !isTabAvailable(selectedTab)))
		{
			active = null;
		}
		SidebarChrome.paintTabRailLine(strip, g, active);
	}
/** Returns the {@link JButton} for the given tab, or {@code null} for a null tab. */
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
/** Repaints the cloud status indicator dot and its ancestor containers, then refreshes account/footer state that depends on it. */
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
		updateManageAccountState();
		updateFooterVisibility();
	}
/** Best-effort footer content width for wrapping the create-profile prompt: footer width if laid out, else derived from the panel width. */
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
/** Styles a tab button and wires its click handler to switch to {@code tab} (ignored if unavailable or already selected). */
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
/** Falls back to the Welcome tab if the current selection became unavailable, then restyles every tab button. */
	private void updateTabStyles()
	{
		if (!isTabAvailable(selectedTab))
		{
			selectedTab = Tab.WELCOME;
		}
		for (Tab tab : Tab.values())
		{
			applyTabStyle(tabButtonFor(tab), tab);
		}
		if (titleTabWrapper != null)
		{
			titleTabWrapper.revalidate();
			titleTabWrapper.repaint();
		}
		updateFooterVisibility();
	}
/** Welcome is always available; the other tabs require being in a game world with no account lock/restricted world. */
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
/**
	 * Recomputes which footer blocks (create-profile prompt, account panel, trade button) are visible
	 * based on world/session/cloud state and the selected tab, plus the spacer gaps between them.
	 */
	private void updateFooterVisibility()
	{
		if (footerHiddenForBlockingState())
		{
			footerPanel.setVisible(false);
			return;
		}
		boolean inWorld = isClientInGameWorld();
		boolean restrictedWorld = cloudSessionService.isRestrictedWorld();
		boolean showCreateProfile = inWorld && !restrictedWorld
			&& cloudSessionService.needsCloudConsent();

		footerPanel.setVisible(true);
		sidebarNoticeView.restoreAccountPanelToFooter();
		createProfileFooterWrap.setVisible(showCreateProfile);
		updateCreateProfileState();

		boolean cloudConnected = cloudSessionService.isSessionActive()
			&& !cloudSessionService.needsCloudConsent();
		boolean showAccountPanel = inWorld && !restrictedWorld && cloudConnected;
		albumFooterWrap.setVisible(showAccountPanel);
		updateManageAccountState();

		boolean showTrade = inWorld
			&& !restrictedWorld
			&& cloudConnected
			&& tradeCloudService.getPendingAccept() != null
			&& selectedTab != Tab.WELCOME;
		tradeFooterWrap.setVisible(showTrade);

		createProfileFooterGap.setVisible(showCreateProfile && (showAccountPanel || showTrade));
		tradeFooterSpacer.setVisible(showTrade && showAccountPanel);

		if (showCreateProfile)
		{
			createProfileController.updatePromptLayout(
				createProfilePromptPane, createProfileFooterWrap, footerContentWidth());
		}
		SidebarLayout.lockFooterBlockHeight(albumFooterWrap);
		SidebarLayout.lockFooterBlockHeight(tradeFooterWrap);
	}
/** Builds the "Open trades" footer button, which opens the {@code /trades} web album page. */
	private JButton createOpenTradesButton()
	{
		JButton button = new JButton(
			"<html><center>Open trades<br>"
				+ "<span style='font-family:SansSerif;font-size:8px;color:#aaaaaa'>You have pending trades waiting...</span>"
				+ "</center></html>");
		button.addActionListener(e -> accountLauncher.open("/trades"));
		return button;
	}
/** Applies enabled/active styling (colors, border, cursor, tooltip) to a single tab button. */
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
/**
	 * How a tab is rendered: {@code NORMAL} builds fresh from live state; {@code FROZEN} reuses/builds
	 * once against the pack-reveal snapshot and is cached per tab in {@link #revealTabBuilt} until the
	 * freeze clears; {@code PACK_CLOSE} rebuilds from a precomputed snapshot right after a reveal closes.
	 */
	private enum TabRenderMode
	{
		NORMAL,
		FROZEN,
		PACK_CLOSE
	}
/** Renders the currently selected tab, using {@code FROZEN} mode while a pack reveal snapshot is active. */
	private void renderSelectedTab()
	{
		TabRenderMode mode = packRevealService.isActive() && sidebarRevealSpoilerSnap != null
			? TabRenderMode.FROZEN
			: TabRenderMode.NORMAL;
		renderTab(selectedTab, mode, null, null, null);
	}
/**
	 * Builds (or, if already built for {@code FROZEN} mode, reuses) the given tab's content per
	 * {@code mode}, then switches the visible card via {@link #showRenderedTab}.
	 */
	private void renderTab(Tab tab, TabRenderMode mode, PackCloseSnapshot snap,
		CloudSidebarCollectionStats metrics, List<BoosterShopRow> shopRows)
	{
		if (mode == TabRenderMode.FROZEN && revealTabBuilt[tab.ordinal()])
		{
			showRenderedTab(tab, mode);
			return;
		}

		switch (tab)
		{
			case WELCOME:
				welcomeContent.removeAll();
				renderWelcomeTab(welcomeContent);
				break;
			case OVERVIEW:
				overviewContent.removeAll();
				if (mode == TabRenderMode.PACK_CLOSE)
				{
					overviewTab.render(overviewContent, snap, metrics);
				}
				else
				{
					renderOverviewTab(overviewContent);
				}
				break;
			case COLLECTION:
				collectionTab.render();
				break;
			case SHOP:
				if (mode == TabRenderMode.PACK_CLOSE)
				{
					shopTab.renderFromPackClose(snap, shopRows);
				}
				else
				{
					shopTab.render();
				}
				break;
		}

		if (mode == TabRenderMode.FROZEN)
		{
			revealTabBuilt[tab.ordinal()] = true;
		}
		showRenderedTab(tab, mode);
	}
/**
	 * Switches the visible content card for {@code tab}, going through {@link #showTabContent} (which
	 * also revalidates scroll panes) except when frozen/pack-close mode should keep showing the
	 * currently-visible card unchanged.
	 */
	private void showRenderedTab(Tab tab, TabRenderMode mode)
	{
		if (mode == TabRenderMode.NORMAL
			|| (mode == TabRenderMode.FROZEN && (tab == Tab.COLLECTION || tab == Tab.SHOP))
			|| (mode == TabRenderMode.PACK_CLOSE && tab == Tab.SHOP))
		{
			showTabContent(tab);
		}
		else
		{
			contentLayout.show(content, tab.name());
		}
	}
/** Switches the content {@link CardLayout} to {@code tab} and revalidates that tab's scroll pane (and shop chrome for the Shop tab). */
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
/** Builds a fresh {@link PackCloseSnapshot} from current state under the state service's lock. */
	private PackCloseSnapshot buildPackCloseSnapshot()
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
/** Returns the frozen reveal snapshot while a pack reveal is active, else builds a fresh one. */
	private PackCloseSnapshot capturePackCloseSnapshot()
	{
		if (sidebarRevealSpoilerSnap != null && packRevealService.isActive())
		{
			return sidebarRevealSpoilerSnap;
		}
		return buildPackCloseSnapshot();
	}
/** Freezes the sidebar to a pre-reveal snapshot (so newly pulled cards don't spoil in the background) and resets built-tab caching. */
	@Override
	public void beginPackRevealSidebarFreeze()
	{
		sidebarRevealSpoilerSnap = capturePackCloseSnapshot();
		resetRevealTabBuilt();
	}
/** Clears the reveal freeze so subsequent renders use live state, and resets built-tab caching. */
	@Override
	public void clearPackRevealSidebarFreeze()
	{
		sidebarRevealSpoilerSnap = null;
		resetRevealTabBuilt();
	}
/** Marks every tab as needing a rebuild under {@link TabRenderMode#FROZEN}. */
	private void resetRevealTabBuilt()
	{
		Arrays.fill(revealTabBuilt, false);
	}
/** Best-effort content width for tab layout: the widest tab scroll pane viewport if laid out, else a fallback derived from panel insets. */
	private int liveSidebarContentWidth()
	{
		int viewportWidth = 0;
		for (JScrollPane sp : tabScrollPanes())
		{
			viewportWidth = Math.max(viewportWidth, sp.getViewport().getWidth());
		}
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
		return Math.max(80, raw - mainPanelHorizontalPad - SidebarLayout.TAB_SCROLLBAR_WIDTH);
	}
/** Content width for the shop pack list: its scroll pane's viewport width if laid out, else {@link #liveSidebarContentWidth()}. */
	private int liveShopPacksContentWidth()
	{
		int viewportWidth = shopPacksScrollPane.getViewport().getWidth();
		if (viewportWidth > 0)
		{
			return Math.max(80, viewportWidth);
		}
		return liveSidebarContentWidth();
	}
/** Builds the border for a tab button: flat outline when disabled, bottom-open outline when active, full outline otherwise. */
	private Border tabBorder(boolean active, boolean enabled)
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
/** Delegates to {@link WelcomeTab#render} with the current content width. */
	private void renderWelcomeTab(JPanel target)
	{
		welcomeTab.render(target, liveSidebarContentWidth());
	}
/** Captures a fresh (or frozen) snapshot and delegates to {@link OverviewTab#render} with its computed metrics. */
	private void renderOverviewTab(JPanel target)
	{
		PackCloseSnapshot snap = capturePackCloseSnapshot();
		overviewTab.render(target, snap, overviewMetrics(snap));
	}
/** Computes overview stats (owned/roll-pool counts etc.) for a snapshot via {@link TcgPublicStatsCalculator}. */
	private CloudSidebarCollectionStats overviewMetrics(PackCloseSnapshot snap)
	{
		List<CardDefinition> all = cardDatabase.getCards();
		return TcgPublicStatsCalculator.resolveOverview(snap, all, all);
	}
/** Returns all four tabs' scroll panes, used to probe for a laid-out viewport width. */
	private JScrollPane[] tabScrollPanes()
	{
		return new JScrollPane[] {
			welcomeScrollPane, overviewScrollPane, collectionListScrollPane, shopPacksScrollPane
		};
	}
/** Returns whether the footer should be hidden entirely because the account is locked or the world is restricted. */
	private boolean footerHiddenForBlockingState()
	{
		return isClientInGameWorld()
			&& (cloudSessionService.isAccountLocked() || cloudSessionService.isRestrictedWorld());
	}
/** Callback from {@link CollectionTab} once its (possibly async) rebuild finishes: shows it and relayouts. */
	private void onCollectionTabRendered()
	{
		showTabContent(Tab.COLLECTION);
		relayoutMainPanel();
	}
/** Shows a full-panel blocking notice, hiding the title tab strip and footer via the callback passed to it. */
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
/** Delegates to {@link AccountPanelLauncher#updateManageAccountState} to refresh the account/trades button state. */
	private void updateManageAccountState()
	{
		accountLauncher.updateManageAccountState(openAccountPanelButton, openTradesButton);
	}
/** Delegates to {@link CreateProfileController#updateButtonState} to refresh the create-profile button state. */
	private void updateCreateProfileState()
	{
		createProfileController.updateButtonState(createProfileButton);
	}
/** Switches to the Overview tab, used after a profile is created. */
	private void selectOverviewAfterCreate()
	{
		if (selectedTab != Tab.OVERVIEW)
		{
			selectedTab = Tab.OVERVIEW;
			updateTabStyles();
		}
	}
/** Post-profile-creation UI refresh: create-profile button state, footer visibility, and cloud status indicator. */
	private void afterCreateProfileUi()
	{
		updateCreateProfileState();
		updateFooterVisibility();
		updateCloudStatusIndicator();
	}
}

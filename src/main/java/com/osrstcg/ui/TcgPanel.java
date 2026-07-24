package com.osrstcg.ui;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.data.BoosterPackDefinition;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.data.PackCatalog;
import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.RewardTuningState;
import com.osrstcg.model.TcgState;
import com.osrstcg.service.CollectionShareService;
import com.osrstcg.service.CreditAwardService;
import com.osrstcg.service.DuplicateSellPlanner;
import com.osrstcg.service.PackOpeningService;
import com.osrstcg.service.PackRevealService;
import com.osrstcg.service.PackSafeModeService;
import com.osrstcg.service.RarityMath;
import com.osrstcg.service.RollPoolFilter;
import com.osrstcg.service.TcgStateService;
import com.osrstcg.ui.collectionalbum.CollectionAlbumManager;
import com.osrstcg.util.NumberFormatting;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
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
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

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

	private static final String TCG_WELCOME_CARD_VALUES_BODY =
		"Card score values are based on each item's in-game value data. They do not reflect the real value of items relative to one another accurately; "
			+ "matching true market prices would require a lot of manual work.";

	private static final String TCG_WELCOME_BETA_BODY =
		"OSRS TCG is still in beta. More booster packs, features, and content are planned for future updates.";

	private static final String TCG_WELCOME_COLLECTION_RESET_BODY =
		"The planned force wipe of beta collections has been cancelled. When the full release update ships, there will be a "
			+ "clear distinction between beta collections and collections started after the update. Existing beta "
			+ "collections will keep their cards and credits and will be able to still open packs, but will no longer be "
			+ "able to trade with other players unless the collection is manually reset using the sidebar panel after the "
			+ "update. A date for this change has not been specified yet.";

	private static final String TCG_WELCOME_DISCLAIMER_HEADER = "Disclaimer";

	private static final String TCG_WELCOME_DISCLAIMER_BODY =
		"This plugin is a fan-made minigame for fun only. Cards have no real-world or in-game monetary value and are not intended to be "
			+ "bought, sold, or traded for real money, bonds, gold, items, or any other goods or services.\n\n"
			+ "Do not pay for cards or collections, and do not accept payment from others for them. If someone offers to sell you cards or asks you "
			+ "to pay for theirs, decline and report them if appropriate.\n\n"
			+ "Trading cards with other players is done at your own risk.";

	private static final String PATREON_URL = "https://www.patreon.com/Azderi";

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
	private final PackSafeModeService packSafeModeService;
	private final PackCatalog packCatalog;
	private final OsrsTcgConfig config;
	private final Client client;
	private final CollectionAlbumManager collectionAlbumManager;
	private final CreditAwardService creditAwardService;
	private final CollectionShareService collectionShareService;
	private final JButton sellDuplicatesButton;

	private final JPanel mainPanel = new JPanel();
	private final JPanel content = new JPanel();
	private final CardLayout contentLayout = new CardLayout();
	private final JPanel welcomeContent = new JPanel();
	private final JPanel overviewContent = new JPanel();
	private final JPanel packsContent = new JPanel();
	private final JScrollPane welcomeScrollPane = new JScrollPane(welcomeContent);
	private final JScrollPane shopScrollPane = new JScrollPane(packsContent);
	private final JPanel footerPanel = new JPanel();
	private final JPanel patreonFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final Component patreonFooterSpacer = Box.createRigidArea(new Dimension(0, 10));
	private final JPanel albumFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final Component albumFooterSpacer = Box.createRigidArea(new Dimension(0, 10));
	private final JPanel resetFooterWrap = new JPanel(new BorderLayout(0, 0));
	private final JPanel titlePanel;
	private final JComponent webShareLiveIndicator;
	private final JButton welcomeTabButton = new JButton(Tab.WELCOME.getLabel());
	private final JButton overviewTabButton = new JButton(Tab.OVERVIEW.getLabel());
	private final JButton shopTabButton = new JButton(Tab.SHOP.getLabel());
	private final Map<String, Long> scoreByCardName = new HashMap<>();
	private Tab selectedTab = Tab.OVERVIEW;
	/** After first in-world refresh, {@link #selectedTab} is only user-driven unless reset clears progress. */
	private boolean defaultTabSelectionInitialized;
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
		PackSafeModeService packSafeModeService,
		PackCatalog packCatalog,
		OsrsTcgConfig config,
		Client client,
		CollectionAlbumManager collectionAlbumManager,
		CreditAwardService creditAwardService,
		CollectionShareService collectionShareService,
		@Named("developerMode") boolean runeliteDeveloperMode)
	{
		super(false);
		this.runeliteDeveloperMode = runeliteDeveloperMode;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.packOpeningService = packOpeningService;
		this.packRevealService = packRevealService;
		this.packSafeModeService = packSafeModeService;
		this.packCatalog = packCatalog;
		this.config = config;
		this.client = client;
		this.collectionAlbumManager = collectionAlbumManager;
		this.creditAwardService = creditAwardService;
		this.collectionShareService = collectionShareService;
		this.sellDuplicatesButton = createSellDuplicatesButton();
		this.webShareLiveIndicator = createWebShareLiveIndicator();

		setLayout(new BorderLayout());

		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setLayout(new BorderLayout(0, 8));
		mainPanel.setBorder(new EmptyBorder(6, 6, 6, 6));

		content.setLayout(contentLayout);
		content.setOpaque(false);
		welcomeContent.setLayout(new BorderLayout());
		welcomeContent.setOpaque(false);
		initializeTabContentPanel(overviewContent);
		initializeTabContentPanel(packsContent);
		content.add(welcomeScrollPane, Tab.WELCOME.name());
		content.add(overviewContent, Tab.OVERVIEW.name());
		content.add(shopScrollPane, Tab.SHOP.name());

		configureTabScrollPane(welcomeScrollPane);
		configureTabScrollPane(shopScrollPane);

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
		updateWebShareLiveIndicator();
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
	 * After the pack reveal overlay closes, recomputes heavy overview stats off the EDT so the client thread returns
	 * quickly, then applies Swing updates on the EDT.
	 */
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
			return;
		}
		ensureRootAttached();
		if (shouldShowLoggedOutPrompt())
		{
			showLoggedOutWelcome();
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
			showTabContent(Tab.SHOP);
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
		updateWebShareLiveIndicator();
		if (shouldShowLoggedOutPrompt())
		{
			showLoggedOutWelcome();
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

		JPanel patreonWrap = patreonFooterWrap;
		patreonWrap.setOpaque(false);
		JButton patreonBtn = createPatreonButton();
		patreonWrap.add(patreonBtn, BorderLayout.CENTER);
		clampPanelWidth(patreonWrap);
		footerPanel.add(patreonWrap);

		footerPanel.add(patreonFooterSpacer);

		JPanel albumWrap = albumFooterWrap;
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

		footerPanel.add(albumFooterSpacer);

		JPanel resetWrap = resetFooterWrap;
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
		selectedTab = Tab.WELCOME;
		updateTabStyles();
		welcomeContent.removeAll();
		renderWelcomeTab(welcomeContent);
		contentLayout.show(content, Tab.WELCOME.name());
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

		// Title row: optically centered label with live-dot above the Shop tab (right).
		JPanel titleRow = new JPanel(new BorderLayout(0, 0));
		titleRow.setOpaque(false);
		titleRow.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(0, 8, 2, 8)
		));
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

		Dimension indicatorSlot = new Dimension(8, 8);
		JPanel leftBalance = new JPanel();
		leftBalance.setOpaque(false);
		leftBalance.setPreferredSize(indicatorSlot);
		leftBalance.setMinimumSize(indicatorSlot);
		leftBalance.setMaximumSize(indicatorSlot);

		JLabel titleLabel = new JLabel("OSRS TCG");
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setAlignmentX(CENTER_ALIGNMENT);

		webShareLiveIndicator.setPreferredSize(indicatorSlot);
		webShareLiveIndicator.setMinimumSize(indicatorSlot);
		webShareLiveIndicator.setMaximumSize(indicatorSlot);
		webShareLiveIndicator.setAlignmentY(CENTER_ALIGNMENT);

		JPanel rightSlot = new JPanel(new GridBagLayout());
		rightSlot.setOpaque(false);
		rightSlot.setPreferredSize(indicatorSlot);
		rightSlot.setMinimumSize(indicatorSlot);
		rightSlot.setMaximumSize(indicatorSlot);
		rightSlot.add(webShareLiveIndicator);

		titleRow.add(leftBalance, BorderLayout.WEST);
		titleRow.add(titleLabel, BorderLayout.CENTER);
		titleRow.add(rightSlot, BorderLayout.EAST);

		JPanel tabWrapper = new JPanel(new GridLayout(1, 3));
		tabWrapper.setOpaque(false);
		tabWrapper.setBorder(new EmptyBorder(2, 0, 0, 0));
		tabWrapper.add(configureTabButton(welcomeTabButton, Tab.WELCOME));
		tabWrapper.add(configureTabButton(overviewTabButton, Tab.OVERVIEW));
		tabWrapper.add(configureTabButton(shopTabButton, Tab.SHOP));

		title.add(titleRow);
		title.add(tabWrapper);
		updateWebShareLiveIndicator();
		return title;
	}

	private JComponent createWebShareLiveIndicator()
	{
		final Color liveGreen = new Color(0x2E, 0xC4, 0x5A);
		final Color errorRed = new Color(0xE0, 0x4B, 0x4B);
		JComponent dot = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				try
				{
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					int size = Math.min(getWidth(), getHeight());
					if (size < 3)
					{
						return;
					}
					int x = (getWidth() - size) / 2;
					int y = (getHeight() - size) / 2;
					Object colorObj = getClientProperty("webShareIndicatorColor");
					Color fill = colorObj instanceof Color ? (Color) colorObj : liveGreen;
					if (!isVisible())
					{
						return;
					}
					g2.setColor(fill);
					g2.fillOval(x, y, size, size);
				}
				finally
				{
					g2.dispose();
				}
			}

			@Override
			public Dimension getPreferredSize()
			{
				return new Dimension(8, 8);
			}

			@Override
			public Dimension getMinimumSize()
			{
				return getPreferredSize();
			}

			@Override
			public Dimension getMaximumSize()
			{
				return getPreferredSize();
			}
		};
		dot.putClientProperty("webShareIndicatorColor", liveGreen);
		dot.putClientProperty("webShareLiveGreen", liveGreen);
		dot.putClientProperty("webShareErrorRed", errorRed);
		dot.setOpaque(false);
		dot.setVisible(false);
		dot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		dot.setToolTipText("Web album");
		dot.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				String url = collectionShareService.getPublicUrl();
				if (url != null && !url.isEmpty())
				{
					LinkBrowser.browse(url);
				}
			}
		});
		return dot;
	}

	public void updateWebShareLiveIndicator()
	{
		if (webShareLiveIndicator == null)
		{
			return;
		}
		CollectionShareService.WebShareIndicatorState state = collectionShareService.getIndicatorState();
		boolean visible = state != CollectionShareService.WebShareIndicatorState.HIDDEN;
		webShareLiveIndicator.setVisible(visible);

		Color liveGreen = (Color) webShareLiveIndicator.getClientProperty("webShareLiveGreen");
		Color errorRed = (Color) webShareLiveIndicator.getClientProperty("webShareErrorRed");
		if (state == CollectionShareService.WebShareIndicatorState.LIVE)
		{
			webShareLiveIndicator.putClientProperty("webShareIndicatorColor", liveGreen);
			String url = collectionShareService.getPublicUrl();
			webShareLiveIndicator.setToolTipText(
				url == null || url.isEmpty()
					? "Web album live — click to open"
					: "Web album live — click to open " + url);
		}
		else if (state == CollectionShareService.WebShareIndicatorState.ERROR)
		{
			webShareLiveIndicator.putClientProperty("webShareIndicatorColor", errorRed);
			String status = collectionShareService.getStatusText();
			webShareLiveIndicator.setToolTipText(
				status == null || status.isEmpty() ? "Web album sync error" : "Web album: " + status);
		}

		Container parent = webShareLiveIndicator.getParent();
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
		webShareLiveIndicator.revalidate();
		webShareLiveIndicator.repaint();
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
		updateFooterVisibility();
	}

	private void updateFooterVisibility()
	{
		boolean inWorld = isClientInGameWorld();
		boolean showPatreon = selectedTab == Tab.WELCOME;

		footerPanel.setVisible(true);
		patreonFooterWrap.setVisible(showPatreon);
		patreonFooterSpacer.setVisible(showPatreon && inWorld);
		albumFooterWrap.setVisible(inWorld);
		albumFooterSpacer.setVisible(inWorld);
		resetFooterWrap.setVisible(inWorld);
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
				showTabContent(Tab.SHOP);
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
		showTabContent(selectedTab);
	}

	private void showTabContent(Tab tab)
	{
		contentLayout.show(content, tab.name());
		if (tab == Tab.SHOP)
		{
			revalidateTabScrollPane(shopScrollPane);
		}
	}

	private static void revalidateTabScrollPane(JScrollPane scrollPane)
	{
		scrollPane.getViewport().revalidate();
		scrollPane.revalidate();
		scrollPane.repaint();
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
		target.add(buildTcgWelcomeBlurb(w), BorderLayout.NORTH);
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
		List<BoosterPackDefinition> boosters = shopVisibleBoosters();
		List<BoosterShopRow> out = new ArrayList<>(boosters.size());
		for (BoosterPackDefinition booster : boosters)
		{
			if (booster == null)
			{
				continue;
			}
			int[] p = shopProgressOwnedTotal(booster, allCards, rollPool, snap.owned);
			out.add(new BoosterShopRow(booster, p[0], p[1], p[2]));
		}
		return out;
	}

	private void renderPacksTabFromPackClose(JPanel target, PackCloseSnapshot snap, List<BoosterShopRow> shopRows)
	{
		target.add(imageStatPanel("Credits", format(snap.credits),"/credits.png"));
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		target.add(sellDuplicatesPanel());
		updateSellDuplicatesButtonState();
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
			JButton buy = createBoosterBuyButton(row.booster, row.progressOwn, row.progressFoilOwn, row.progressTotal);
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
		target.add(imageStatPanel("Credits", format(snap.credits), "/credits.png"));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Opened packs", format(snap.openedPacks)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Unique cards", format(m.uniqueOwned) + " / " + format(m.totalCardPool)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Unique foil cards", format(m.uniqueFoilOwned) + " / " + format(m.totalCardPool)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Total cards", format(m.totalCardsOwned)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Foil cards", format(m.foilOwned)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Collection %", String.format("%.1f%%", m.completionPct)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Collection Foil %", String.format("%.1f%%", m.foilCompletionPct)));
		target.add(Box.createRigidArea(new Dimension(0, 6)));
		target.add(statPanel("Collection score", format(m.collectionScore)));
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		addRewardTuningOverviewSection(target, state);
	}

	private static final class BoosterShopRow
	{
		private final BoosterPackDefinition booster;
		private final int progressOwn;
		private final int progressFoilOwn;
		private final int progressTotal;

		private BoosterShopRow(BoosterPackDefinition booster, int progressOwn, int progressFoilOwn, int progressTotal)
		{
			this.booster = booster;
			this.progressOwn = progressOwn;
			this.progressFoilOwn = progressFoilOwn;
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
		private final int uniqueFoilOwned;
		private final int totalCardsOwned;
		private final long foilOwned;
		private final int totalCardPool;
		private final double completionPct;
		private final double foilCompletionPct;
		private final long collectionScore;

		private OverviewMetrics(int uniqueOwned, int uniqueFoilOwned, int totalCardsOwned, long foilOwned,
			int totalCardPool, double completionPct, double foilCompletionPct, long collectionScore)
		{
			this.uniqueOwned = uniqueOwned;
			this.uniqueFoilOwned = uniqueFoilOwned;
			this.totalCardsOwned = totalCardsOwned;
			this.foilOwned = foilOwned;
			this.totalCardPool = totalCardPool;
			this.completionPct = completionPct;
			this.foilCompletionPct = foilCompletionPct;
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
			int uniqueOwned = (int) collectedNamesFromOwned(owned).stream()
				.filter(rollPoolNames::contains)
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
			int uniqueFoilOwned = (int) owned.keySet().stream()
				.filter(k -> k.isFoil()
					&& k.getCardName() != null
					&& rollPoolNames.contains(k.getCardName()))
				.filter(k ->
				{
					Integer qty = owned.get(k);
					return qty != null && qty > 0;
				})
				.count();
			int totalCardPool = rollPool.size();
			double completion = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueOwned) / totalCardPool;
			double foilCompletion = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueFoilOwned) / totalCardPool;

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
			return new OverviewMetrics(uniqueOwned, uniqueFoilOwned, totalCardsOwned, foilOwned, totalCardPool,
				completion, foilCompletion, collectionScore);
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
		cb.setToolTipText("Enables developer testing features.");
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

	private static JTextPane buildWelcomeTextArea(int contentMaxW, String text, int topGap)
	{
		return buildWelcomeTextArea(contentMaxW, text, topGap, new Color(0xBBBBBB), 0);
	}

	private static JTextPane buildWelcomeTextArea(int contentMaxW, String text, int topGap, int bottomGap)
	{
		return buildWelcomeTextArea(contentMaxW, text, topGap, new Color(0xBBBBBB), bottomGap);
	}

	private static JTextPane buildWelcomeTextArea(int contentMaxW, String text, int topGap, Color foreground)
	{
		return buildWelcomeTextArea(contentMaxW, text, topGap, foreground, 0);
	}

	private static JTextPane buildWelcomeTextArea(int contentMaxW, String text, int topGap, Color foreground, int bottomGap)
	{
		int w = Math.max(1, contentMaxW);
		JTextPane tp = new JTextPane();
		tp.setEditable(false);
		tp.setOpaque(false);
		tp.setFocusable(false);
		tp.setForeground(foreground);
		tp.setFont(FontManager.getRunescapeSmallFont());
		tp.setText(text);
		tp.setBorder(new EmptyBorder(topGap, 0, bottomGap, 0));
		tp.setAlignmentX(CENTER_ALIGNMENT);

		SimpleAttributeSet attrs = new SimpleAttributeSet();
		StyleConstants.setAlignment(attrs, StyleConstants.ALIGN_CENTER);
		StyleConstants.setFontFamily(attrs, tp.getFont().getFamily());
		StyleConstants.setFontSize(attrs, tp.getFont().getSize());
		StyleConstants.setForeground(attrs, foreground);
		StyledDocument doc = tp.getStyledDocument();
		doc.setParagraphAttributes(0, doc.getLength(), attrs, false);
		doc.setCharacterAttributes(0, doc.getLength(), attrs, false);

		tp.setSize(w, Short.MAX_VALUE);
		int bodyH = tp.getPreferredSize().height;
		tp.setPreferredSize(new Dimension(w, bodyH));
		tp.setMaximumSize(new Dimension(w, bodyH));
		return tp;
	}

	private static JPanel buildTcgWelcomeBlurb(int contentMaxW)
	{
		int w = Math.max(1, contentMaxW);

		JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setOpaque(false);
		wrap.setAlignmentX(CENTER_ALIGNMENT);
		wrap.setBorder(new EmptyBorder(8, 0, 0, 0));

		JLabel head = new JLabel(TCG_WELCOME_HEADER);
		head.setForeground(Color.WHITE);
		head.setFont(FontManager.getRunescapeBoldFont());
		head.setHorizontalAlignment(SwingConstants.CENTER);
		head.setAlignmentX(CENTER_ALIGNMENT);
		wrap.add(head);

		wrap.add(buildWelcomeTextArea(w, TCG_WELCOME_BODY, 6));

		JButton discordButton = createDiscordButton(w);
		if (discordButton != null)
		{
			discordButton.setAlignmentX(CENTER_ALIGNMENT);
			wrap.add(Box.createRigidArea(new Dimension(0, 8)));
			wrap.add(discordButton);
		}

		wrap.add(buildWelcomeTextArea(w, TCG_WELCOME_TCG_COMMAND_BODY, 10));
		wrap.add(buildWelcomeTextArea(w, TCG_WELCOME_CARD_VALUES_BODY, 10));
		wrap.add(buildWelcomeTextArea(w, TCG_WELCOME_BETA_BODY, 10));
		wrap.add(buildWelcomeTextArea(w, TCG_WELCOME_COLLECTION_RESET_BODY, 10, Color.YELLOW));

		JLabel disclaimerHead = new JLabel(TCG_WELCOME_DISCLAIMER_HEADER);
		disclaimerHead.setForeground(Color.WHITE);
		disclaimerHead.setFont(FontManager.getRunescapeBoldFont());
		disclaimerHead.setHorizontalAlignment(SwingConstants.CENTER);
		disclaimerHead.setAlignmentX(CENTER_ALIGNMENT);
		disclaimerHead.setBorder(new EmptyBorder(10, 0, 0, 0));
		wrap.add(disclaimerHead);

		wrap.add(buildWelcomeTextArea(w, TCG_WELCOME_DISCLAIMER_BODY, 6, 6));

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

	private static final int TAB_SCROLLBAR_WIDTH = 6;
	private static final int TAB_SCROLLBAR_GAP = 10;
	private static final int TAB_SCROLLBAR_RESERVED_WIDTH = TAB_SCROLLBAR_WIDTH + TAB_SCROLLBAR_GAP;

	/** Usable width inside the plugin sidebar (matches shop grid / booster buttons); leaves room for the tab scroll panes' vertical scrollbar so content isn't clipped once it appears. */
	private static int sidebarInnerWidth()
	{
		return Math.max(160, PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET - TAB_SCROLLBAR_RESERVED_WIDTH);
	}

	/**
	 * Horizontal space for tab content. Prefers the tab scroll panes' actual, already-laid-out viewport width
	 * (guaranteed to match reality, however wide the real scrollbar ends up rendering) over estimating it from
	 * {@link #getWidth()} minus a handful of assumed constants, which can drift from the real value and clip text.
	 * Falls back to that estimate only before the panes have ever been shown.
	 */
	private int liveSidebarContentWidth()
	{
		int viewportWidth = Math.max(welcomeScrollPane.getViewport().getWidth(), shopScrollPane.getViewport().getWidth());
		if (viewportWidth > 0)
		{
			return Math.max(80, viewportWidth - TAB_SCROLLBAR_GAP);
		}

		Insets pi = getInsets();
		int raw = getWidth() - pi.left - pi.right;
		if (raw <= 0)
		{
			return sidebarInnerWidth();
		}
		int mainPanelHorizontalPad = 12;
		return Math.max(80, raw - mainPanelHorizontalPad - TAB_SCROLLBAR_RESERVED_WIDTH);
	}

	private static void configureTabScrollPane(JScrollPane scrollPane)
	{
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);
		scrollPane.setWheelScrollingEnabled(true);

		JScrollBar vbar = scrollPane.getVerticalScrollBar();
		vbar.setUnitIncrement(16);
		vbar.setOpaque(false);
		vbar.putClientProperty(FlatClientProperties.STYLE,
			"width:" + TAB_SCROLLBAR_WIDTH + "; trackArc:999; thumbArc:999; trackInsets:0,2,0,2; thumbInsets:0,2,0,2; "
				+ "track:#00000000; thumb:#4D4D4D; hoverThumbColor:#787878; showButtons:false");
				
		scrollPane.addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && scrollPane.isShowing())
			{
				SwingUtilities.updateComponentTreeUI(vbar);
			}
		});
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

	/** Distinct card names with at least one foil copy owned. */
	private static Set<String> foilCollectedNamesFromOwned(Map<CardCollectionKey, Integer> owned)
	{
		Set<String> foilNames = new HashSet<>();
		for (Map.Entry<CardCollectionKey, Integer> entry : owned.entrySet())
		{
			CardCollectionKey key = entry.getKey();
			if (key == null || !key.isFoil())
			{
				continue;
			}
			String cardName = key.getCardName();
			Integer qty = entry.getValue();
			if (cardName != null && qty != null && qty > 0)
			{
				foilNames.add(cardName);
			}
		}
		return foilNames;
	}

	/**
	 * Shop progress: Standard (empty category) = distinct names in the roll pool; regional = distinct names in the full
	 * catalog that match {@link BoosterPackDefinition#cardMatchesRegion} (same OR / AND rules as pack filters).
	 * Returns {@code [standardOwned, foilOwned, total]}.
	 */
	private static int[] shopProgressOwnedTotal(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool,
		Map<CardCollectionKey, Integer> owned)
	{
		Set<String> collectedNames = collectedNamesFromOwned(owned);
		Set<String> foilCollectedNames = foilCollectedNamesFromOwned(owned);
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
		int foilOwn = (int) eligible.stream().filter(foilCollectedNames::contains).count();
		return new int[] { own, foilOwn, total };
	}

	private void renderPacksTab(JPanel target)
	{
		PackCloseSnapshot displaySnap = capturePackCloseSnapshotForDisplay();
		target.add(imageStatPanel("Credits", format(displaySnap.credits),"/credits.png"));
		target.add(Box.createRigidArea(new Dimension(0, 8)));
		target.add(sellDuplicatesPanel());
		updateSellDuplicatesButtonState();
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

	private JPanel imageStatPanel(String labelText, String valueText, String imagePath)
	{
		JPanel panel = new JPanel(new BorderLayout(8, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(4, 6, 4, 6));

		JPanel left = new JPanel(new BorderLayout(6, 0));
		left.setOpaque(false);

		URL imgUrl = TcgPanel.class.getResource(imagePath);
		if (imgUrl != null)
		{
			ImageIcon icon = new ImageIcon(imgUrl);
			JLabel iconLabel = new JLabel(icon);
			iconLabel.setHorizontalAlignment(SwingConstants.LEFT);
			left.add(iconLabel, BorderLayout.WEST);
		}

		JLabel label = textPanel(shorten(labelText, 24));
		label.setToolTipText(labelText);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		left.add(label, BorderLayout.CENTER);

		JLabel value = textPanel(valueText);
		value.setHorizontalAlignment(SwingConstants.RIGHT);

		panel.add(left, BorderLayout.CENTER);
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
		JLabel label = new JLabel(text)
		{
			@Override
			public void updateUI()
			{
				super.updateUI();
				applySidebarStatLabelStyle(this);
			}
		};
		applySidebarStatLabelStyle(label);
		return label;
	}

	private static void applySidebarStatLabelStyle(JLabel label)
	{
		label.setForeground(Color.WHITE);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
	}

	private static final int SHOP_BOOSTER_GRID_GAP = 6;

	private List<BoosterPackDefinition> shopVisibleBoosters()
	{
		List<BoosterPackDefinition> boosters = new ArrayList<>(
			packCatalog.getVisibleBoosters(stateService.isDebugLogging()));
		boosters.sort(Comparator
			.comparingInt((BoosterPackDefinition b) -> b == null ? Integer.MAX_VALUE : b.getPrice())
			.thenComparing(b -> b == null || b.getName() == null ? "" : b.getName(),
				String.CASE_INSENSITIVE_ORDER));
		return boosters;
	}

	private JPanel boosterShopPanel(PackCloseSnapshot displaySnap)
	{
		JPanel outer = new JPanel();
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setOpaque(false);

		List<BoosterPackDefinition> boosters = shopVisibleBoosters();
		if (boosters.isEmpty())
		{
			outer.add(infoPanel("No booster packs configured. Add Packs.json to plugin resources."));
			clampPanelWidth(outer);
			return outer;
		}

		Map<CardCollectionKey, Integer> owned = displaySnap.owned;
		List<CardDefinition> allCards = cardDatabase.getCards();
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(allCards);

		JPanel grid = new JPanel(new GridLayout(0, 2, SHOP_BOOSTER_GRID_GAP, SHOP_BOOSTER_GRID_GAP));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);

		boolean revealBusy = packRevealService.isActive();
		boolean packOpeningBlocked = packSafeModeService.isPackOpeningBlocked();
		long credits = displaySnap.credits;
		for (BoosterPackDefinition booster : boosters)
		{
			JButton buy = createBoosterBuyButton(booster, allCards, rollPool, owned);
			int price = booster.getPrice();
			buy.setEnabled(!revealBusy && !packOpeningBlocked && credits >= price);
			if (packOpeningBlocked)
			{
				String blockMessage = packSafeModeService.packOpeningBlockMessage();
				buy.setToolTipText(blockMessage == null ? null : blockMessage);
			}
			else
			{
				buy.setToolTipText(null);
			}
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

	private static final int SHOP_PROGRESS_BAR_WIDTH_PX = 75;
	private static final int SHOP_PROGRESS_BAR_HEIGHT_PX = 6;
	private static final String SHOP_DEFAULT_PACK_THUMBNAIL = "Pack_Standard_thumbnail.png";

	private static URL shopPackIconUrl(BoosterPackDefinition booster)
	{
		String thumbnail = booster == null ? null : booster.getThumbnail();
		if (thumbnail != null && !thumbnail.trim().isEmpty())
		{
			URL url = TcgPanel.class.getResource("/" + thumbnail.trim());
			if (url != null)
			{
				return url;
			}
		}
		return TcgPanel.class.getResource("/" + SHOP_DEFAULT_PACK_THUMBNAIL);
	}

	private JButton createBoosterBuyButton(BoosterPackDefinition booster, int progressOwn, int progressFoilOwn, int progressTotal)
	{
		int price = booster.getPrice();
		String title = booster.getName() == null ? "Booster" : booster.getName();
		double progressPct = progressTotal <= 0 ? 0.0 : (100.0 * progressOwn) / progressTotal;

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);

		JLabel titleLabel = shopBoosterTextLabel(htmlEscape(title));
		content.add(titleLabel);

		URL packIconUrl = shopPackIconUrl(booster);
		if (packIconUrl != null)
		{
			JLabel iconLabel = new JLabel(new ImageIcon(packIconUrl));
			iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			iconLabel.setBorder(new EmptyBorder(0, 0, 5, 0));
			content.add(iconLabel);
		}

		content.add(shopBoosterTextLabel(format(price) + " credits"));

		content.add(new ShopPackProgressBar(SHOP_PROGRESS_BAR_WIDTH_PX, progressOwn, progressFoilOwn, progressTotal));
		content.add(shopBoosterTextLabel(format(progressOwn) + " / " + format(progressTotal)));
		content.add(shopBoosterTextLabel("(" + String.format("%.0f", progressPct) + "%)"));

		JButton button = new JButton();
		button.setLayout(new BorderLayout());
		button.add(content, BorderLayout.CENTER);
		button.setIcon(null);
		button.setHorizontalTextPosition(SwingConstants.CENTER);
		button.setVerticalTextPosition(SwingConstants.CENTER);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setForeground(Color.WHITE);
		button.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(6, 6, 8, 6)
		));
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusable(false);
		button.setPreferredSize(new Dimension(shopBoosterButtonWidth(), 120));

		button.addActionListener(e ->
		{
			if (packRevealService.isActive())
			{
				refresh();
				return;
			}
			if (packSafeModeService.isPackOpeningBlocked())
			{
				String blockMessage = packSafeModeService.packOpeningBlockMessage();
				if (client != null && blockMessage != null)
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"[OSRS TCG] " + blockMessage, null);
				}
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

	private static JLabel shopBoosterTextLabel(String text)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER)
		{
			@Override
			public void updateUI()
			{
				super.updateUI();
				applyShopBoosterTextLabelStyle(this);
			}
		};
		applyShopBoosterTextLabelStyle(label);
		return label;
	}

	private static void applyShopBoosterTextLabelStyle(JLabel label)
	{
		label.setAlignmentX(Component.CENTER_ALIGNMENT);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());
	}

	private static final class ShopPackProgressBar extends JPanel
	{
		private static final Color TRACK = new Color(0x2e2e2e);
		private static final Color TRACK_BORDER = new Color(0x555555);
		private static final Color STANDARD_FILL = new Color(0x4caf50);
		private static final Color FOIL_FILL = new Color(0xF2C94C);

		private final int barWidthPx;
		private final int standardFillPx;
		private final int foilFillPx;

		private ShopPackProgressBar(int barWidthPx, int standardOwn, int foilOwn, int total)
		{
			this.barWidthPx = barWidthPx;
			double standardPct = total <= 0 ? 0.0 : (100.0 * standardOwn) / total;
			double foilPct = total <= 0 ? 0.0 : (100.0 * foilOwn) / total;
			this.standardFillPx = (int) Math.round(barWidthPx * Math.min(100.0, Math.max(0.0, standardPct)) / 100.0);
			this.foilFillPx = (int) Math.round(barWidthPx * Math.min(100.0, Math.max(0.0, foilPct)) / 100.0);
			int outerW = barWidthPx + 2;
			int outerH = SHOP_PROGRESS_BAR_HEIGHT_PX + 2;
			Dimension size = new Dimension(outerW, outerH);
			setOpaque(false);
			setPreferredSize(size);
			setMinimumSize(size);
			setMaximumSize(size);
			setAlignmentX(Component.CENTER_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				int x = 1;
				int y = 1;
				int h = SHOP_PROGRESS_BAR_HEIGHT_PX;
				g2.setColor(TRACK_BORDER);
				g2.fillRect(0, 0, barWidthPx + 2, h + 2);
				g2.setColor(TRACK);
				g2.fillRect(x, y, barWidthPx, h);
				if (standardFillPx > 0)
				{
					g2.setColor(STANDARD_FILL);
					g2.fillRect(x, y, standardFillPx, h);
				}
				if (foilFillPx > 0)
				{
					g2.setColor(FOIL_FILL);
					g2.fillRect(x, y, foilFillPx, h);
				}
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	private JButton createBoosterBuyButton(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool,
		Map<CardCollectionKey, Integer> owned)
	{
		int[] progress = shopProgressOwnedTotal(booster, allCards, rollPool, owned);
		return createBoosterBuyButton(booster, progress[0], progress[1], progress[2]);
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

	private void updateSellDuplicatesButtonState()
	{
		List<OwnedCardInstance> instances = stateService.getState().getCollectionState().getOwnedInstances();
		boolean hasDuplicates = DuplicateSellPlanner.hasSellableDuplicates(instances, config.keepVersion(),
			cardDatabase.displayTiersByCardName()::get, config.keepTier());
		sellDuplicatesButton.setEnabled(hasDuplicates);
		sellDuplicatesButton.setToolTipText(hasDuplicates ? null : "No duplicate cards to sell.");
	}

	private void promptAndSellDuplicates()
	{
		TcgState current = stateService.getState();
		List<OwnedCardInstance> all = new ArrayList<>(current.getCollectionState().getOwnedInstances());
		if (all.isEmpty())
		{
			refresh();
			return;
		}

		DuplicateSellPlanner.Result plan = DuplicateSellPlanner.plan(all, this::cardDefinitionForName, config.keepVersion(),
			cardDatabase.displayTiersByCardName()::get, config.keepTier());
		int cardsSold = plan.getCardsSold();
		long creditsToAdd = plan.getCreditsToAdd();

		if (cardsSold <= 0)
		{
			refresh();
			return;
		}

		int choice = JOptionPane.showConfirmDialog(
			this,
			"Are you sure you want to sell " + cardsSold + " cards for " + format(creditsToAdd) + " credits?",
			"Sell duplicates",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}

		stateService.setCollectionInstances(plan.getKept());
		if (creditsToAdd > 0L)
		{
			stateService.addCredits(creditsToAdd);
		}
		refresh();
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

	private CardDefinition cardDefinitionForName(String cardName)
	{
		if (cardName == null)
		{
			return null;
		}
		String n = cardName.trim();
		if (n.isEmpty())
		{
			return null;
		}
		for (CardDefinition c : cardDatabase.getCards())
		{
			if (c.getName() != null && c.getName().equals(n))
			{
				return c;
			}
		}
		return null;
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

	private static JButton createPatreonButton()
	{
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);

		JLabel top = new JLabel("Support my projects on", SwingConstants.CENTER);
		top.setFont(FontManager.getRunescapeSmallFont());
		top.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		top.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel bottom = new JLabel("PATREON", SwingConstants.CENTER);
		bottom.setFont(FontManager.getRunescapeBoldFont());
		bottom.setForeground(Color.WHITE);
		bottom.setAlignmentX(Component.CENTER_ALIGNMENT);

		content.add(top);
		content.add(bottom);

		JButton button = new JButton();
		button.setLayout(new BorderLayout());
		button.add(content, BorderLayout.CENTER);
		button.setFocusable(false);
		button.setFocusPainted(false);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.LIGHT_GRAY_COLOR.darker()),
			new EmptyBorder(8, 14, 8, 14)
		));
		button.setToolTipText("Support on Patreon");
		button.addActionListener(e -> LinkBrowser.browse(PATREON_URL));
		return button;
	}

	private static JButton createDiscordButton(int contentMaxW)
	{
		URL imgUrl = TcgPanel.class.getResource("/Discord-Logo-White.png");
		if (imgUrl != null)
		{
			int buttonH = 36;
			int hPad = 14;
			int vPad = 8;

			ImageIcon rawIcon = new ImageIcon(imgUrl);
			int iconH = buttonH - (2 * vPad);
			int iconW = Math.max(1, Math.round(iconH * (rawIcon.getIconWidth() / (float) rawIcon.getIconHeight())));
			Image scaled = rawIcon.getImage().getScaledInstance(iconW, iconH, Image.SCALE_SMOOTH);

			var discordButton = new JButton(new ImageIcon(scaled));
			discordButton.setToolTipText("Join our Discord!");
			discordButton.setBorder(new CompoundBorder(
				new MatteBorder(1, 1, 1, 1, ColorScheme.LIGHT_GRAY_COLOR.darker()),
				new EmptyBorder(vPad, hPad, vPad, hPad)
			));
			Dimension size = new Dimension(Math.max(1, contentMaxW), buttonH);
			discordButton.setPreferredSize(size);
			discordButton.setMaximumSize(size);
			discordButton.addActionListener(e -> LinkBrowser.browse("https://discord.gg/P4pPu6RnCj"));
			return discordButton;
		}

		return null;
	}
}

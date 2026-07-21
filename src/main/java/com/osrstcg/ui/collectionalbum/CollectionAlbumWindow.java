package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.BoosterPackDefinition;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.data.PackCatalog;
import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.TcgState;
import com.osrstcg.service.CardPartyTradeService;
import com.osrstcg.service.CardPartyTransferService;
import com.osrstcg.service.DuplicateSellCredits;
import com.osrstcg.service.RarityMath;
import com.osrstcg.service.TcgStateService;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.SharedCardRenderer;
import com.osrstcg.util.CollectionAlbumWindowSizeUtil;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

public final class CollectionAlbumWindow extends JFrame
{
	private static final BufferedImage WINDOW_ICON =
		ImageUtil.loadImageResource(CollectionAlbumWindow.class, "/icon.png");

	private static final String VIEW_ALBUM_BROWSE = "browse";
	private static final String VIEW_CARD_VARIANTS = "variants";
	private static final String VIEW_NORTH_BROWSE = "northBrowse";
	private static final String VIEW_NORTH_VARIANT = "northVariant";

	private static final String PARTY_SEND_TOOLTIP =
		"You and the recipient must both be in the same RuneLite party with OSRS TCG installed to send cards.";
	private static final String LOCKED_CARD_ACTION_TOOLTIP = AlbumInstanceTooltip.LOCKED_ACTION_HINT;
	private static final int PAGE_SIZE = 21;
	/** Quiet period after the last visible wiki-image load before one coalesced album repaint. */
	private static final int IMAGE_REPAINT_DEBOUNCE_MS = 500;
	private static final String RARITY_FILTER_ALL = "All";
	private static final List<String> RARITY_TIERS_LOW_TO_HIGH = List.of(
		"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic", "Godly");

	private final CardDatabase cardDatabase;
	private final TcgStateService stateService;
	private final PackCatalog packCatalog;
	private final WikiImageCacheService imageCacheService;
	private final PartyService partyService;
	private final CardPartyTransferService cardPartyTransferService;
	private final CardPartyTradeService cardPartyTradeService;
	private final Runnable sidebarRefresh;

	private final List<Long> partyMemberIds = new ArrayList<>();
	private final JComboBox<String> partyMemberCombo = new JComboBox<>();
	private final JButton sendCardBtn = new JButton("Send");
	private final JButton sendTradeOfferBtn = new JButton("Send a trade offer");
	private final JButton acceptTradeBtn = new JButton("Accept trade request");
	private final JButton offerForTradeBtn = new JButton("Offer up for trade");
	private final JButton sellCardBtn = new JButton("Sell");
	private final JLabel sendStatusLabel = new JLabel(" ");
	private final Timer partyUiTimer;
	private Timer statusFlashTimer;

	private AlbumRarityTable rarityTable = AlbumRarityTable.build(List.of());
	private List<TabFilter> tabFilters = List.of();
	private final JComboBox<String> collectionCombo = new JComboBox<>();
	private boolean suppressCollectionComboEvents;
	private final JTextField searchField = new JTextField(18);
	private final JComboBox<AlbumSortMode> sortCombo = new JComboBox<>(AlbumSortMode.values());
	private final JComboBox<String> rarityCombo = new JComboBox<>();
	private final JRadioButton radCardsAll = new JRadioButton("All cards", true);
	private final JRadioButton radObtained = new JRadioButton("Obtained only");
	private final JRadioButton radDuplicates = new JRadioButton("Duplicates only");
	private final JRadioButton radMissing = new JRadioButton("Missing only");
	private final JCheckBox foilOnlyCheck = new JCheckBox("Foil only");
	private final JButton prevBtn = new JButton("< Prev");
	private final JButton nextBtn = new JButton("Next >");
	private final JLabel pageLabel = new JLabel(" ");
	private final CollectionAlbumGridPanel grid;
	private final CardLayout albumCenterLayout = new CardLayout();
	private final JPanel albumCenterHost = new JPanel(albumCenterLayout);
	private final CollectionAlbumVariantsPanel variantsPanel;
	private Timer searchDebounceTimer;
	/**
	 * Debounces wiki-image load completions: restart on each visible-card arrival, then one
	 * grid repaint after quiet. Avoids flooding the AWT queue (shared with client mouse/camera).
	 */
	private Timer imageRepaintDebounceTimer;
	private final Consumer<String> imageLoadListener = this::onWikiImageLoaded;
	/** High-rate foil sparkle/sheen repaints while foil cards are visible. */
	private final Timer foilAnimTimer;

	private final CardLayout albumNorthLayout = new CardLayout();
	private final JPanel albumNorthHost = new JPanel(albumNorthLayout);
	private final JPanel variantNorthBanner = new JPanel(new BorderLayout(16, 0));
	private final JButton variantBackToAlbumBtn = new JButton("< Back to album");
	private final JLabel variantCardTitleLbl = new JLabel(" ", JLabel.CENTER);
	private final JButton variantPagingPrevBtn = new JButton("< Prev");
	private final JButton variantPagingNextBtn = new JButton("Next >");
	private final JLabel variantPagingLabel = new JLabel(" ");

	private boolean albumVariantsVisible;

	/** When true, {@link #setVisible(true)} runs after the first page model+images are ready. */
	private boolean pendingShowWhenPageReady;

	/** Normalized wiki URLs for cards currently shown; read from image-load threads. */
	private volatile Set<String> visibleImageUrls = Set.of();

	/** True when {@link #sendChosenInstanceId} was chosen from the variant grid (no album cell selection). */
	private boolean sendPickFromVariantOnly;

	private int pageIndex;
	private int filteredTotal;
	private int pageCount;
	/** Filtered + sorted card list for the active tab; paging reuses this without re-sorting. */
	private List<CardDefinition> filteredSortedCards = List.of();
	/** Selected collection row for party send; cleared when changing cards or after a successful send. */
	private String sendChosenInstanceId;
	private String sendFocusCardName;
	/** Last size from user resize; used when persisting on hide (getSize() can be wrong while closing). */
	private Dimension trackedWindowSize;
	private final AtomicLong modelRebuildGen = new AtomicLong();

	public CollectionAlbumWindow(
		CardDatabase cardDatabase,
		TcgStateService stateService,
		PackCatalog packCatalog,
		WikiImageCacheService imageCacheService,
		PartyService partyService,
		CardPartyTransferService cardPartyTransferService,
		CardPartyTradeService cardPartyTradeService,
		Runnable sidebarRefresh)
	{
		super("OSRS TCG — Collection album");
		if (WINDOW_ICON != null)
		{
			setIconImage(WINDOW_ICON);
		}
		this.cardDatabase = cardDatabase;
		this.stateService = stateService;
		this.packCatalog = packCatalog;
		this.imageCacheService = imageCacheService;
		this.partyService = partyService;
		this.cardPartyTransferService = cardPartyTransferService;
		this.cardPartyTradeService = cardPartyTradeService;
		this.sidebarRefresh = sidebarRefresh;
		this.grid = new CollectionAlbumGridPanel(imageCacheService,
			this::onOwnedMultiCopyAlbumPress, this::onAlbumCardLockToggle, this::onSlotSelectionChanged,
			this::onAlbumDoubleClickOffer);
		this.variantsPanel = new CollectionAlbumVariantsPanel(imageCacheService, this::onVariantInstancePicked,
			this::onVariantCardLockToggle, this::onVariantDoubleClickOffer);

		setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(
			CollectionAlbumWindowSizeUtil.MIN_WIDTH,
			CollectionAlbumWindowSizeUtil.MIN_HEIGHT));
		setLayout(new BorderLayout(8, 8));
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);

		rarityCombo.addItem(RARITY_FILTER_ALL);
		for (String tier : RARITY_TIERS_LOW_TO_HIGH)
		{
			rarityCombo.addItem(tier);
		}
		rarityCombo.setSelectedIndex(0);
		rarityCombo.setForeground(Color.WHITE);
		rarityCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rarityCombo.addActionListener(e ->
		{
			pageIndex = 0;
			rebuildModel();
		});

		collectionCombo.setForeground(Color.WHITE);
		collectionCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		collectionCombo.setMaximumRowCount(16);
		int comboH = collectionCombo.getPreferredSize().height;
		collectionCombo.setPreferredSize(new Dimension(480, Math.max(comboH, 24)));
		collectionCombo.setMinimumSize(new Dimension(240, Math.max(comboH, 24)));
		collectionCombo.addActionListener(e ->
		{
			if (suppressCollectionComboEvents)
			{
				return;
			}
			pageIndex = 0;
			rebuildModel();
		});

		sortCombo.setForeground(Color.WHITE);
		sortCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sortCombo.addActionListener(e ->
		{
			pageIndex = 0;
			rebuildModel();
		});

		searchField.setColumns(20);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			private void schedule()
			{
				pageIndex = 0;
				if (searchDebounceTimer == null)
				{
					searchDebounceTimer = new Timer(220, ev -> rebuildModel());
					searchDebounceTimer.setRepeats(false);
				}
				searchDebounceTimer.restart();
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				schedule();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				schedule();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				schedule();
			}
		});

		ButtonGroup ownGroup = new ButtonGroup();
		ownGroup.add(radCardsAll);
		ownGroup.add(radObtained);
		ownGroup.add(radDuplicates);
		ownGroup.add(radMissing);
		styleRadio(radCardsAll);
		styleRadio(radObtained);
		styleRadio(radDuplicates);
		styleRadio(radMissing);
		radCardsAll.addActionListener(e ->
		{
			pageIndex = 0;
			rebuildModel();
		});
		radObtained.addActionListener(e ->
		{
			pageIndex = 0;
			rebuildModel();
		});
		radDuplicates.addActionListener(e ->
		{
			pageIndex = 0;
			rebuildModel();
		});
		radMissing.addActionListener(e ->
		{
			pageIndex = 0;
			rebuildModel();
		});

		foilOnlyCheck.setForeground(Color.WHITE);
		foilOnlyCheck.setOpaque(false);
		foilOnlyCheck.addActionListener(e ->
		{
			pageIndex = 0;
			rebuildModel();
		});

		prevBtn.addActionListener(e ->
		{
			pageIndex = Math.max(0, pageIndex - 1);
			refreshCurrentPage();
		});
		nextBtn.addActionListener(e ->
		{
			pageIndex = Math.min(Math.max(0, pageCount - 1), pageIndex + 1);
			refreshCurrentPage();
		});

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);
		top.setBorder(new EmptyBorder(4, 8, 4, 8));

		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setOpaque(false);
		controls.setAlignmentX(Component.CENTER_ALIGNMENT);

		JPanel collectionRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
		collectionRow.setOpaque(false);
		JLabel collLbl = new JLabel("Collection:");
		collLbl.setForeground(Color.WHITE);
		collectionRow.add(collLbl);
		collectionRow.add(collectionCombo);
		collectionRow.setAlignmentX(Component.CENTER_ALIGNMENT);
		collectionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, collectionRow.getPreferredSize().height));
		controls.add(collectionRow);

		JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
		filterRow.setOpaque(false);
		JLabel searchLbl = new JLabel("Search:");
		searchLbl.setForeground(Color.WHITE);
		filterRow.add(searchLbl);
		filterRow.add(searchField);
		JLabel sortLbl = new JLabel("Sort:");
		sortLbl.setForeground(Color.WHITE);
		filterRow.add(sortLbl);
		filterRow.add(sortCombo);
		JLabel rlab = new JLabel("Rarity:");
		rlab.setForeground(Color.WHITE);
		filterRow.add(rlab);
		filterRow.add(rarityCombo);
		filterRow.add(radCardsAll);
		filterRow.add(radObtained);
		filterRow.add(radDuplicates);
		filterRow.add(radMissing);
		filterRow.add(Box.createHorizontalStrut(4));
		filterRow.add(foilOnlyCheck);
		filterRow.setAlignmentX(Component.CENTER_ALIGNMENT);
		filterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, filterRow.getPreferredSize().height));
		controls.add(filterRow);

		JPanel row4 = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 2));
		row4.setOpaque(false);
		row4.add(prevBtn);
		row4.add(pageLabel);
		row4.add(nextBtn);
		pageLabel.setForeground(Color.WHITE);
		row4.setAlignmentX(Component.CENTER_ALIGNMENT);
		row4.setMaximumSize(new Dimension(Integer.MAX_VALUE, row4.getPreferredSize().height));
		controls.add(row4);

		top.add(controls);
		MouseWheelListener pageWheel = this::onAlbumMouseWheel;
		top.addMouseWheelListener(pageWheel);
		collectionRow.addMouseWheelListener(pageWheel);
		collectionCombo.addMouseWheelListener(pageWheel);
		for (Component c : row4.getComponents())
		{
			c.addMouseWheelListener(pageWheel);
		}
		row4.addMouseWheelListener(pageWheel);

		JPanel browseNorthHost = new JPanel(new BorderLayout());
		browseNorthHost.setOpaque(false);
		browseNorthHost.add(top, BorderLayout.CENTER);
		browseNorthHost.addMouseWheelListener(pageWheel);

		variantNorthBanner.setOpaque(false);
		variantNorthBanner.setBorder(new EmptyBorder(6, 8, 6, 8));
		variantBackToAlbumBtn.setFocusable(false);
		variantBackToAlbumBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		variantBackToAlbumBtn.setForeground(Color.WHITE);
		variantBackToAlbumBtn.setMargin(new Insets(10, 14, 10, 14));
		variantBackToAlbumBtn.addActionListener(e -> exitAlbumVariantView());
		JPanel variantBackCol = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		variantBackCol.setOpaque(false);
		variantBackCol.add(variantBackToAlbumBtn);
		variantNorthBanner.add(variantBackCol, BorderLayout.WEST);

		variantCardTitleLbl.setForeground(Color.WHITE);
		variantCardTitleLbl.setFont(FontManager.getRunescapeBoldFont());
		variantNorthBanner.add(variantCardTitleLbl, BorderLayout.CENTER);

		JPanel variantPagingRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		variantPagingRow.setOpaque(false);
		variantPagingPrevBtn.setFocusable(false);
		variantPagingNextBtn.setFocusable(false);
		variantPagingPrevBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		variantPagingNextBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		variantPagingPrevBtn.setForeground(Color.WHITE);
		variantPagingNextBtn.setForeground(Color.WHITE);
		variantPagingLabel.setForeground(Color.WHITE);
		variantPagingRow.add(variantPagingPrevBtn);
		variantPagingRow.add(variantPagingLabel);
		variantPagingRow.add(variantPagingNextBtn);
		variantNorthBanner.add(variantPagingRow, BorderLayout.EAST);

		albumNorthHost.setOpaque(false);
		albumNorthHost.add(browseNorthHost, VIEW_NORTH_BROWSE);
		albumNorthHost.add(variantNorthBanner, VIEW_NORTH_VARIANT);
		add(albumNorthHost, BorderLayout.NORTH);

		variantsPanel.setPagingControls(variantPagingPrevBtn, variantPagingNextBtn, variantPagingLabel);

		JPanel browseWrap = new JPanel(new BorderLayout());
		browseWrap.setOpaque(false);
		grid.setBorder(BorderFactory.createEmptyBorder(4, 6, 12, 6));
		browseWrap.add(grid, BorderLayout.CENTER);
		browseWrap.addMouseWheelListener(pageWheel);
		grid.addMouseWheelListener(pageWheel);

		variantsPanel.setBorder(BorderFactory.createEmptyBorder(4, 6, 12, 6));
		variantsPanel.addMouseWheelListener(pageWheel);

		albumCenterHost.setOpaque(false);
		albumCenterHost.add(browseWrap, VIEW_ALBUM_BROWSE);
		albumCenterHost.add(variantsPanel, VIEW_CARD_VARIANTS);
		add(albumCenterHost, BorderLayout.CENTER);

		JPanel south = new JPanel(new BorderLayout(8, 6));
		south.setOpaque(false);
		south.setBorder(new EmptyBorder(0, 8, 6, 8));

		JPanel partyColumn = new JPanel();
		partyColumn.setOpaque(false);
		partyColumn.setLayout(new BoxLayout(partyColumn, BoxLayout.Y_AXIS));

		JPanel partyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		partyRow.setOpaque(false);
		JLabel partyLbl = new JLabel("Party:");
		partyLbl.setForeground(Color.WHITE);
		partyRow.add(partyLbl);
		partyMemberCombo.setForeground(Color.WHITE);
		partyMemberCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		partyRow.add(partyMemberCombo);
		styleSouthBarButton(sendCardBtn);
		partyRow.add(sendCardBtn);
		styleSouthBarButton(sendTradeOfferBtn);
		partyRow.add(sendTradeOfferBtn);
		styleSouthBarButton(acceptTradeBtn);
		acceptTradeBtn.setVisible(false);
		partyRow.add(acceptTradeBtn);
		partyRow.add(sendStatusLabel);
		partyColumn.add(partyRow);

		JPanel sellRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		sellRow.setOpaque(false);
		styleSouthBarButton(offerForTradeBtn);
		offerForTradeBtn.setVisible(false);
		offerForTradeBtn.setEnabled(false);
		sellRow.add(offerForTradeBtn);
		styleSouthBarButton(sellCardBtn);
		sellCardBtn.setEnabled(false);
		sellRow.add(sellCardBtn);

		south.add(partyColumn, BorderLayout.WEST);
		south.add(sellRow, BorderLayout.EAST);
		partyMemberCombo.addActionListener(e -> updateSouthBarButtons());
		sendCardBtn.addActionListener(this::onSendToPartyClicked);
		sendTradeOfferBtn.addActionListener(this::onSendTradeOfferClicked);
		acceptTradeBtn.addActionListener(e -> onAcceptTradeClicked());
		offerForTradeBtn.addActionListener(e -> onOfferForTradeClicked());
		sellCardBtn.addActionListener(this::onSellSelectedCardClicked);
		add(south, BorderLayout.SOUTH);

		partyUiTimer = new Timer(2000, e ->
		{
			if (isShowing() && partyService.isInParty())
			{
				refreshPartyMemberCombo();
				refreshPartyTradeChrome();
			}
		});

		imageRepaintDebounceTimer = new Timer(IMAGE_REPAINT_DEBOUNCE_MS, e ->
		{
			if (!isShowing())
			{
				return;
			}
			grid.refreshFacesAfterImageLoad();
			if (albumVariantsVisible)
			{
				variantsPanel.repaint();
			}
		});
		imageRepaintDebounceTimer.setRepeats(false);
		imageCacheService.addLoadListener(imageLoadListener);

		// Continuous foil animation; paint path only blits cached faces + cheap overlays.
		foilAnimTimer = new Timer(SharedCardRenderer.FOIL_SPARKLE_FRAME_MS, e ->
		{
			if (!isShowing())
			{
				return;
			}
			if (grid.hasVisibleFoilCards())
			{
				grid.repaint();
			}
			if (albumVariantsVisible && variantsPanel.hasVisibleFoilCards())
			{
				variantsPanel.repaint();
			}
		});

		styleFrameFonts();

		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				Dimension size = getSize();
				if (size.width > 0 && size.height > 0)
				{
					trackedWindowSize = size;
				}
			}
		});
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				persistWindowSize();
			}

			@Override
			public void windowOpened(WindowEvent e)
			{
				scheduleApplySavedWindowSize();
			}
		});
		applySavedWindowSize();
	}

	/** Re-applies persisted size before showing (layout during first build can reset early setSize). */
	void prepareToShow()
	{
		applySavedWindowSize();
		refreshPartyTradeChrome();
	}

	/**
	 * Show the frame only after the next successful model apply (images awaited off-EDT).
	 * Avoids overlapping first-open disk decode GC with client middle-mouse camera input.
	 */
	void requestShowWhenPageReady()
	{
		pendingShowWhenPageReady = true;
	}

	private void finishPendingShow()
	{
		if (!pendingShowWhenPageReady)
		{
			return;
		}
		pendingShowWhenPageReady = false;
		setVisible(true);
		toFront();
	}

	@Override
	public void setVisible(boolean visible)
	{
		if (!visible && isShowing())
		{
			persistWindowSize();
			stopTimers();
		}
		super.setVisible(visible);
		if (visible)
		{
			scheduleApplySavedWindowSize();
			startTimers();
		}
	}

	private void styleRadio(JRadioButton r)
	{
		r.setForeground(Color.WHITE);
		r.setOpaque(false);
	}

	private void styleFrameFonts()
	{
		java.awt.Font small = FontManager.getRunescapeSmallFont();
		searchField.setFont(small);
		sortCombo.setFont(small);
		rarityCombo.setFont(small);
		collectionCombo.setFont(small);
		prevBtn.setFont(small);
		nextBtn.setFont(small);
		pageLabel.setFont(small);
		radCardsAll.setFont(small);
		radObtained.setFont(small);
		radDuplicates.setFont(small);
		radMissing.setFont(small);
		foilOnlyCheck.setFont(small);
		partyMemberCombo.setFont(small);
		variantBackToAlbumBtn.setFont(small);
		variantPagingPrevBtn.setFont(small);
		variantPagingNextBtn.setFont(small);
		variantPagingLabel.setFont(small);
		variantCardTitleLbl.setFont(FontManager.getRunescapeBoldFont());
		sendCardBtn.setFont(small);
		sendTradeOfferBtn.setFont(small);
		acceptTradeBtn.setFont(small);
		offerForTradeBtn.setFont(small);
		sellCardBtn.setFont(small);
		sendStatusLabel.setFont(small);
		syncSouthBarButtonHeights();
	}

	private static void styleSouthBarButton(JButton btn)
	{
		btn.setFocusable(false);
		btn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		btn.setForeground(Color.WHITE);
		btn.setFont(FontManager.getRunescapeSmallFont());
		btn.setMargin(new Insets(3, 10, 3, 10));
	}

	private void syncSouthBarButtonHeights()
	{
		JButton[] buttons = {
			sendCardBtn, sendTradeOfferBtn, acceptTradeBtn, offerForTradeBtn, sellCardBtn
		};
		for (JButton btn : buttons)
		{
			btn.setPreferredSize(null);
			btn.setMinimumSize(null);
			btn.setMaximumSize(null);
		}
		int height = 0;
		for (JButton btn : buttons)
		{
			height = Math.max(height, btn.getPreferredSize().height);
		}
		if (height <= 0)
		{
			return;
		}
		for (JButton btn : buttons)
		{
			Dimension pref = btn.getPreferredSize();
			btn.setPreferredSize(new Dimension(Math.max(1, pref.width), height));
			btn.setMinimumSize(new Dimension(0, height));
			btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		}
		partyMemberCombo.setPreferredSize(null);
		Dimension comboPref = partyMemberCombo.getPreferredSize();
		int comboH = Math.max(comboPref.height, height);
		partyMemberCombo.setPreferredSize(new Dimension(comboPref.width, comboH));
	}

	private void stopTimers()
	{
		partyUiTimer.stop();
		foilAnimTimer.stop();
		if (imageRepaintDebounceTimer != null)
		{
			imageRepaintDebounceTimer.stop();
		}
		if (searchDebounceTimer != null)
		{
			searchDebounceTimer.stop();
		}
		if (statusFlashTimer != null)
		{
			statusFlashTimer.stop();
		}
	}

	private void startTimers()
	{
		if (partyService.isInParty())
		{
			partyUiTimer.start();
		}
		updateAlbumRepaintTimers();
	}

	/** Start/stop continuous foil sparkle timer when foil cards are on screen. */
	private void updateAlbumRepaintTimers()
	{
		if (!isShowing())
		{
			foilAnimTimer.stop();
			return;
		}

		boolean hasFoil = grid.hasVisibleFoilCards()
			|| (albumVariantsVisible && variantsPanel.hasVisibleFoilCards());
		if (hasFoil)
		{
			if (!foilAnimTimer.isRunning())
			{
				foilAnimTimer.start();
			}
		}
		else
		{
			foilAnimTimer.stop();
		}
	}

	/**
	 * Visible wiki-image arrivals only. Repaint once when every currently shown URL has settled,
	 * so decode bursts do not spam the AWT queue shared with client mouse/camera.
	 */
	private void onWikiImageLoaded(String normalizedUrl)
	{
		if (normalizedUrl == null || normalizedUrl.isEmpty())
		{
			return;
		}
		Set<String> visible = visibleImageUrls;
		if (visible.isEmpty() || !visible.contains(normalizedUrl))
		{
			return;
		}
		for (String url : visible)
		{
			if (!imageCacheService.isSettled(url))
			{
				return;
			}
		}
		SwingUtilities.invokeLater(() ->
		{
			if (!isShowing())
			{
				return;
			}
			imageRepaintDebounceTimer.restart();
		});
	}

	private void rememberVisibleImageUrls(List<AlbumSlot> pageSlots)
	{
		Set<String> next = new HashSet<>();
		if (pageSlots != null)
		{
			for (AlbumSlot slot : pageSlots)
			{
				if (slot == null || slot.card() == null || slot.card().getImageUrl() == null)
				{
					continue;
				}
				String normalized = imageCacheService.normalizeImageUrl(slot.card().getImageUrl());
				if (!normalized.isEmpty())
				{
					next.add(normalized);
				}
			}
		}
		visibleImageUrls = next;
	}

	private void rememberBrowsePageImageUrls()
	{
		if (filteredSortedCards.isEmpty())
		{
			visibleImageUrls = Set.of();
			return;
		}
		int from = pageIndex * PAGE_SIZE;
		int to = Math.min(from + PAGE_SIZE, filteredSortedCards.size());
		Set<String> next = new HashSet<>();
		for (int i = from; i < to; i++)
		{
			CardDefinition c = filteredSortedCards.get(i);
			if (c == null || c.getImageUrl() == null)
			{
				continue;
			}
			String normalized = imageCacheService.normalizeImageUrl(c.getImageUrl());
			if (!normalized.isEmpty())
			{
				next.add(normalized);
			}
		}
		visibleImageUrls = next;
	}

	private void rememberVariantImageUrl(CardDefinition card)
	{
		if (card == null || card.getImageUrl() == null)
		{
			visibleImageUrls = Set.of();
			return;
		}
		String normalized = imageCacheService.normalizeImageUrl(card.getImageUrl());
		visibleImageUrls = normalized.isEmpty() ? Set.of() : Set.of(normalized);
	}

	void disposeInternal()
	{
		imageCacheService.removeLoadListener(imageLoadListener);
		persistWindowSize();
		stopTimers();
		dispose();
	}

	private void scheduleApplySavedWindowSize()
	{
		SwingUtilities.invokeLater(this::applySavedWindowSize);
	}

	private void applySavedWindowSize()
	{
		TcgState s = stateService.getState();
		Dimension size = CollectionAlbumWindowSizeUtil.resolve(s.getAlbumWindowWidth(), s.getAlbumWindowHeight());
		setSize(size);
		trackedWindowSize = size;
	}

	private void persistWindowSize()
	{
		Dimension size = trackedWindowSize;
		if (size == null || size.width <= 0 || size.height <= 0)
		{
			size = getSize();
		}
		if (size.width <= 0 || size.height <= 0)
		{
			return;
		}
		trackedWindowSize = size;
		stateService.setAlbumWindowSize(size.width, size.height);
	}

	public void refreshData()
	{
		cardDatabase.load();
		// Colours are precomputed once in CardDatabase.load(); do not re-tier the catalog on the EDT.
		rarityTable = AlbumRarityTable.fromColorByCardName(cardDatabase.displayRarityColorsByCardName());
		tabFilters = buildTabFilters();
		suppressCollectionComboEvents = true;
		try
		{
			collectionCombo.removeAllItems();
			for (TabFilter tf : tabFilters)
			{
				collectionCombo.addItem(tf.getTitle());
			}
			if (!tabFilters.isEmpty())
			{
				collectionCombo.setSelectedIndex(0);
			}
		}
		finally
		{
			suppressCollectionComboEvents = false;
		}
		pageIndex = 0;
		rebuildModel();
	}

	private List<TabFilter> buildTabFilters()
	{
		List<TabFilter> out = new ArrayList<>();
		out.add(new TabFilter("All", CollectionAlbumWindow::hasCardName));
		for (BoosterPackDefinition b : packCatalog.getVisibleBoosters(stateService.isDebugLogging()))
		{
			if (b == null)
			{
				continue;
			}
			List<String> filters = b.getCategoryFilters();
			if (filters.isEmpty())
			{
				// Universal pack (e.g. Standard): same card set as "All" — omit duplicate tab.
				continue;
			}
			String fallbackTitle = b.getId() == null || b.getId().isEmpty() ? "Booster" : b.getId();
			String title = b.getName() == null || b.getName().isEmpty() ? fallbackTitle : b.getName();
			out.add(new TabFilter(title, card -> BoosterPackDefinition.cardMatchesRegion(card, filters)));
		}
		return out;
	}

	private static boolean hasCardName(CardDefinition c)
	{
		return c != null && c.getName() != null && !c.getName().trim().isEmpty();
	}

	private void onAlbumMouseWheel(MouseWheelEvent e)
	{
		if (pageCount <= 1)
		{
			return;
		}
		int next = Math.max(0, Math.min(pageCount - 1, pageIndex + e.getWheelRotation()));
		if (next != pageIndex)
		{
			pageIndex = next;
			refreshCurrentPage();
		}
		e.consume();
	}

	public void rebuildModel()
	{
		scheduleModelRebuild(true);
	}

	/**
	 * Lightweight refresh when collection data changes externally (trade, gift, sell).
	 * Avoids resetting variant view or rebuilding the party combo; heavy filter/sort runs off the EDT.
	 */
	void refreshFromCollectionChange()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshFromCollectionChange);
			return;
		}
		if (!isShowing())
		{
			return;
		}
		if (needsFullRefilterForCollectionChange())
		{
			scheduleModelRebuild(false);
			return;
		}
		if (albumVariantsVisible)
		{
			refreshActiveVariantCopies();
		}
		else
		{
			refreshCurrentPage();
		}
		updateSouthBarButtons();
	}

	private boolean needsFullRefilterForCollectionChange()
	{
		return radObtained.isSelected()
			|| radDuplicates.isSelected()
			|| radMissing.isSelected()
			|| foilOnlyCheck.isSelected();
	}

	private void scheduleModelRebuild(boolean userInitiated)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> scheduleModelRebuild(userInitiated));
			return;
		}
		if (userInitiated)
		{
			exitAlbumVariantView();
			refreshPartyMemberCombo();
		}
		int collectionIdx = collectionCombo.getSelectedIndex();
		if (tabFilters.isEmpty() || collectionIdx < 0 || collectionIdx >= tabFilters.size())
		{
			applyEmptyFilteredModel();
			return;
		}

		ModelRebuildInputs inputs = captureModelRebuildInputs(collectionIdx);
		final long gen = modelRebuildGen.incrementAndGet();
		ForkJoinPool.commonPool().execute(() ->
		{
			List<CardDefinition> working = computeFilteredSortedCards(inputs);
			int pages = Math.max(1, (working.size() + PAGE_SIZE - 1) / PAGE_SIZE);
			int page = Math.max(0, Math.min(inputs.preservePageIndex, pages - 1));
			int from = page * PAGE_SIZE;
			int to = Math.min(from + PAGE_SIZE, working.size());
			imageCacheService.preloadAndAwait(imageUrlsBetween(working, from, to), 8_000L);
			SwingUtilities.invokeLater(() -> applyModelRebuild(gen, inputs.preservePageIndex, working));
		});
	}

	private void applyEmptyFilteredModel()
	{
		filteredSortedCards = List.of();
		filteredTotal = 0;
		pageCount = 1;
		pageIndex = 0;
		rememberVisibleImageUrls(List.of());
		grid.setSlots(List.of(), selectionPreserveIndex(List.of()));
		updatePageControls(0, 0);
		finishPendingShow();
	}

	private ModelRebuildInputs captureModelRebuildInputs(int collectionIdx)
	{
		AlbumSortMode mode = (AlbumSortMode) sortCombo.getSelectedItem();
		if (mode == null)
		{
			mode = AlbumSortMode.SCORE_DESC;
		}
		return new ModelRebuildInputs(
			collectionIdx,
			cardDatabase.getCards(),
			tabFilters,
			rarityTable,
			(String) rarityCombo.getSelectedItem(),
			searchField.getText().trim().toLowerCase(Locale.ROOT),
			stateService.getState().getCollectionState().getOwnedCards(),
			mostRecentPulledAtByCardName(stateService.getState().getCollectionState().getLastObtainedMap()),
			foilOnlyCheck.isSelected(),
			radObtained.isSelected(),
			radDuplicates.isSelected(),
			radMissing.isSelected(),
			mode,
			pageIndex);
	}

	private void applyModelRebuild(long gen, int preservePageIndex, List<CardDefinition> working)
	{
		if (gen != modelRebuildGen.get())
		{
			return;
		}
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> applyModelRebuild(gen, preservePageIndex, working));
			return;
		}
		filteredSortedCards = working;
		filteredTotal = working.size();
		pageCount = Math.max(1, (filteredTotal + PAGE_SIZE - 1) / PAGE_SIZE);
		pageIndex = Math.max(0, Math.min(preservePageIndex, pageCount - 1));
		// Images for this page were awaited off-EDT in scheduleModelRebuild.
		refreshCurrentPage(true);
		finishPendingShow();
	}

	private static List<CardDefinition> computeFilteredSortedCards(ModelRebuildInputs inputs)
	{
		if (inputs.tabFilters.isEmpty()
			|| inputs.collectionIdx < 0
			|| inputs.collectionIdx >= inputs.tabFilters.size())
		{
			return List.of();
		}

		Predicate<CardDefinition> tabPred = inputs.tabFilters.get(inputs.collectionIdx).getInclude();
		List<CardDefinition> working = inputs.allCards.stream()
			.filter(CollectionAlbumWindow::hasCardName)
			.filter(tabPred)
			.collect(Collectors.toCollection(ArrayList::new));

		String rarityPick = inputs.rarityPick;
		if (rarityPick != null && !RARITY_FILTER_ALL.equals(rarityPick))
		{
			working.removeIf(c -> !rarityPick.equals(inputs.rarityTable.tierLabelForCard(c)));
		}

		String q = inputs.searchQuery;
		if (q != null && !q.isEmpty())
		{
			working.removeIf(c -> c.getName() == null || !c.getName().toLowerCase(Locale.ROOT).contains(q));
		}

		Map<CardCollectionKey, Integer> owned = inputs.owned;
		Set<String> collected = collectedNamesFromOwned(owned);

		if (inputs.foilOnly)
		{
			working.removeIf(c -> !hasFoilOwned(owned, c.getName()));
		}

		if (inputs.obtainedOnly)
		{
			working.removeIf(c -> !collected.contains(c.getName()));
		}
		else if (inputs.duplicatesOnly)
		{
			working.removeIf(c -> !hasDuplicateOwned(owned, c.getName()));
		}
		else if (inputs.missingOnly)
		{
			working.removeIf(c -> collected.contains(c.getName()));
		}

		Comparator<CardDefinition> byName = Comparator.comparing(
			c -> c.getName() == null ? "" : c.getName(),
			String.CASE_INSENSITIVE_ORDER);
		switch (inputs.sortMode)
		{
			case SCORE_DESC:
				working.sort(Comparator.<CardDefinition>comparingDouble(c -> albumSortScore(owned, c))
					.reversed()
					.thenComparing(byName));
				break;
			case MOST_RECENT:
				working.sort(Comparator.<CardDefinition>comparingLong(
					c -> mostRecentPulledAt(inputs.mostRecentPulledAtByName, c.getName()))
					.reversed()
					.thenComparing(byName));
				break;
			case RARITY_DESC:
				working.sort(Comparator.<CardDefinition>comparingInt(
					c -> tierSortKey(inputs.rarityTable.tierLabelForCard(c)))
					.reversed()
					.thenComparing(byName));
				break;
			case NAME_ASC:
			default:
				working.sort(byName);
				break;
		}
		return working;
	}

	/** Updates the visible page from {@link #filteredSortedCards} without re-filtering or re-sorting. */
	private void refreshCurrentPage()
	{
		refreshCurrentPage(false);
	}

	/**
	 * @param imagesPreloaded when false, may bounce through a background preload-await so the EDT
	 *                        paint runs with art already in memory (avoids decode GC during camera use).
	 */
	private void refreshCurrentPage(boolean imagesPreloaded)
	{
		if (filteredSortedCards.isEmpty())
		{
			rememberVisibleImageUrls(List.of());
			grid.setSlots(List.of(), selectionPreserveIndex(List.of()));
			updatePageControls(0, 0);
			return;
		}

		pageIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
		int from = pageIndex * PAGE_SIZE;
		int to = Math.min(from + PAGE_SIZE, filteredTotal);
		List<String> pageUrls = imageUrlsBetween(filteredSortedCards, from, to);
		if (!imagesPreloaded && pageUrls.stream().anyMatch(u -> !imageCacheService.isSettled(u)))
		{
			final int pageSnap = pageIndex;
			final long gen = modelRebuildGen.get();
			ForkJoinPool.commonPool().execute(() ->
			{
				imageCacheService.preloadAndAwait(pageUrls, 6_000L);
				SwingUtilities.invokeLater(() ->
				{
					if (gen != modelRebuildGen.get() || pageSnap != pageIndex)
					{
						return;
					}
					refreshCurrentPage(true);
				});
			});
			return;
		}

		Map<CardCollectionKey, Integer> owned = stateService.getState().getCollectionState().getOwnedCards();
		Set<String> collected = collectedNamesFromOwned(owned);
		Map<String, List<OwnedCardInstance>> instancesByName = indexInstancesByName(
			stateService.getState().getCollectionState().getOwnedInstances());

		List<AlbumSlot> slots = new ArrayList<>();
		for (int i = from; i < to; i++)
		{
			CardDefinition c = filteredSortedCards.get(i);
			String name = c.getName();
			Color rarity = rarityTable.colorForCardName(name);
			boolean ownAny = collected.contains(name);
			boolean displayFoil = hasFoilOwned(owned, name);
			Integer nf = owned.get(new CardCollectionKey(name, false));
			Integer ff = owned.get(new CardCollectionKey(name, true));
			int nQty = nf == null ? 0 : nf;
			int fQty = ff == null ? 0 : ff;
			List<OwnedCardInstance> row = instancesByName.getOrDefault(name, List.of());
			String singleTip = singleCopyAlbumHoverTooltip(name, nQty, fQty, ownAny, row);
			boolean lockBadge = false;
			String soleInstanceId = null;
			boolean offeredInTrade = false;
			if (ownAny)
			{
				lockBadge = row.stream().anyMatch(OwnedCardInstance::isLocked);
				if (row.size() == 1)
				{
					soleInstanceId = row.get(0).getInstanceId();
				}
				if (cardPartyTradeService.isTradeActive())
				{
					for (OwnedCardInstance inst : row)
					{
						if (inst != null && cardPartyTradeService.isInstanceOfferedLocally(inst.getInstanceId()))
						{
							offeredInTrade = true;
							break;
						}
					}
				}
			}
			slots.add(new AlbumSlot(c, rarity, ownAny, displayFoil, nQty, fQty, singleTip, lockBadge, soleInstanceId,
				offeredInTrade));
		}
		rememberVisibleImageUrls(slots);
		grid.setSlots(slots, selectionPreserveIndex(slots));
		updatePageControls(from, to);
		updateAlbumRepaintTimers();
	}

	private void updatePageControls(int from, int to)
	{
		int startN = filteredTotal == 0 ? 0 : from + 1;
		int endN = filteredTotal == 0 ? 0 : to;
		pageLabel.setText(String.format("Page %s / %s   (%s - %s of %s)",
			NumberFormatting.format(pageIndex + 1), NumberFormatting.format(pageCount),
			NumberFormatting.format(startN), NumberFormatting.format(endN), NumberFormatting.format(filteredTotal)));
		prevBtn.setEnabled(pageIndex > 0);
		nextBtn.setEnabled(pageIndex < pageCount - 1);
	}

	/** Index to re-select after rebuild when the focused card is still on the current page. */
	private int selectionPreserveIndex(List<AlbumSlot> pageSlots)
	{
		if (albumVariantsVisible || sendPickFromVariantOnly || sendFocusCardName == null)
		{
			return -1;
		}
		String focus = sendFocusCardName.trim();
		if (focus.isEmpty())
		{
			return -1;
		}
		for (int i = 0; i < pageSlots.size(); i++)
		{
			AlbumSlot slot = pageSlots.get(i);
			if (slot == null || !slot.ownedAny() || slot.card() == null || slot.card().getName() == null)
			{
				continue;
			}
			if (focus.equals(slot.card().getName().trim()))
			{
				return i;
			}
		}
		return -1;
	}

	private static List<String> imageUrlsBetween(List<CardDefinition> ordered, int from, int to)
	{
		List<String> urls = new ArrayList<>();
		if (ordered == null)
		{
			return urls;
		}
		int lo = Math.max(0, from);
		int hi = Math.min(ordered.size(), Math.max(lo, to));
		for (int i = lo; i < hi; i++)
		{
			CardDefinition c = ordered.get(i);
			if (c != null && c.getImageUrl() != null && !c.getImageUrl().trim().isEmpty())
			{
				urls.add(c.getImageUrl());
			}
		}
		return urls;
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

	private static Set<String> collectedNamesFromOwned(Map<CardCollectionKey, Integer> owned)
	{
		Map<String, Integer> qtyByName = new HashMap<>();
		for (Map.Entry<CardCollectionKey, Integer> e : owned.entrySet())
		{
			if (e.getKey() == null || e.getKey().getCardName() == null)
			{
				continue;
			}
			int q = e.getValue() == null ? 0 : e.getValue();
			qtyByName.merge(e.getKey().getCardName(), q, Integer::sum);
		}
		Set<String> names = new HashSet<>();
		for (Map.Entry<String, Integer> e : qtyByName.entrySet())
		{
			if (e.getValue() != null && e.getValue() > 0)
			{
				names.add(e.getKey());
			}
		}
		return names;
	}

	/** Score for album sort: foil-adjusted when the player owns a foil copy, else base score. */
	private static double albumSortScore(Map<CardCollectionKey, Integer> owned, CardDefinition card)
	{
		if (card == null)
		{
			return 0.0d;
		}
		if (hasFoilOwned(owned, card.getName()))
		{
			return RarityMath.foilAdjustedScoreRounded(card);
		}
		return RarityMath.score(card);
	}

	/** Max {@code pulledAt} per card name from foil/non-foil last-obtained timestamps. */
	private static Map<String, Long> mostRecentPulledAtByCardName(Map<CardCollectionKey, Long> lastObtained)
	{
		Map<String, Long> byName = new HashMap<>();
		if (lastObtained == null || lastObtained.isEmpty())
		{
			return byName;
		}
		for (Map.Entry<CardCollectionKey, Long> e : lastObtained.entrySet())
		{
			if (e.getKey() == null || e.getKey().getCardName() == null)
			{
				continue;
			}
			long at = e.getValue() == null ? 0L : e.getValue();
			byName.merge(e.getKey().getCardName(), at, Math::max);
		}
		return byName;
	}

	private static long mostRecentPulledAt(Map<String, Long> byName, String cardName)
	{
		if (byName == null || cardName == null)
		{
			return 0L;
		}
		Long at = byName.get(cardName);
		return at == null ? 0L : at;
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

	/** True when combined foil + non-foil quantity for the card name is greater than one. */
	private static boolean hasDuplicateOwned(Map<CardCollectionKey, Integer> owned, String cardName)
	{
		if (cardName == null)
		{
			return false;
		}
		int total = 0;
		Integer normal = owned.get(new CardCollectionKey(cardName, false));
		Integer foil = owned.get(new CardCollectionKey(cardName, true));
		if (normal != null)
		{
			total += normal;
		}
		if (foil != null)
		{
			total += foil;
		}
		return total > 1;
	}

	private String singleCopyAlbumHoverTooltip(String cardName, int nQty, int fQty, boolean ownAny,
		List<OwnedCardInstance> row)
	{
		if (!ownAny || cardName == null || nQty + fQty != 1)
		{
			return null;
		}
		if (row == null || row.size() != 1)
		{
			return null;
		}
		return AlbumInstanceTooltip.format(row.get(0));
	}

	private static Map<String, List<OwnedCardInstance>> indexInstancesByName(List<OwnedCardInstance> instances)
	{
		if (instances == null || instances.isEmpty())
		{
			return Map.of();
		}
		Map<String, List<OwnedCardInstance>> out = new HashMap<>();
		for (OwnedCardInstance inst : instances)
		{
			if (inst == null || inst.getCardName() == null)
			{
				continue;
			}
			String name = inst.getCardName().trim();
			if (name.isEmpty())
			{
				continue;
			}
			out.computeIfAbsent(name, k -> new ArrayList<>()).add(inst);
		}
		return out;
	}

	private void exitAlbumVariantView()
	{
		if (!albumVariantsVisible)
		{
			return;
		}
		albumNorthLayout.show(albumNorthHost, VIEW_NORTH_BROWSE);
		albumCenterLayout.show(albumCenterHost, VIEW_ALBUM_BROWSE);
		albumVariantsVisible = false;
		rememberBrowsePageImageUrls();
		grid.clearSelection();
		sendChosenInstanceId = null;
		sendFocusCardName = null;
		sendPickFromVariantOnly = false;
		updateSouthBarButtons();
		updateAlbumRepaintTimers();
	}

	private void enterAlbumVariantView(AlbumSlot slot)
	{
		if (slot == null || slot.card() == null || slot.card().getName() == null)
		{
			return;
		}
		String cardName = slot.card().getName().trim();
		if (cardName.isEmpty())
		{
			return;
		}
		List<OwnedCardInstance> copies = new ArrayList<>(
			stateService.getState().getCollectionState().instancesForCardName(cardName));
		if (copies.size() < 2)
		{
			return;
		}
		copies.sort(Comparator.comparing(OwnedCardInstance::isFoil).reversed()
			.thenComparingLong(OwnedCardInstance::getPulledAtEpochMs));
		Color rarity = slot.rarityColor();
		String prevFocus = sendFocusCardName == null ? "" : sendFocusCardName.trim();
		if (!cardName.equals(prevFocus))
		{
			sendChosenInstanceId = null;
		}
		sendFocusCardName = cardName;
		sendPickFromVariantOnly = false;
		variantCardTitleLbl.setText(cardName);
		albumNorthLayout.show(albumNorthHost, VIEW_NORTH_VARIANT);
		variantsPanel.setVariants(slot.card(), rarity, copies, sendChosenInstanceId);
		if (sendChosenInstanceId != null && !sendChosenInstanceId.isEmpty())
		{
			sendPickFromVariantOnly = stateService.getState().getCollectionState()
				.findInstanceById(sendChosenInstanceId)
				.filter(o ->
				{
					String n = o.getCardName() == null ? "" : o.getCardName().trim();
					return cardName.equals(n);
				})
				.isPresent();
		}
		albumCenterLayout.show(albumCenterHost, VIEW_CARD_VARIANTS);
		albumVariantsVisible = true;
		rememberVariantImageUrl(slot.card());
		updateAlbumRepaintTimers();
	}

	private void onVariantInstancePicked(OwnedCardInstance inst)
	{
		if (inst == null)
		{
			return;
		}
		sendChosenInstanceId = inst.getInstanceId();
		String n = inst.getCardName() == null ? "" : inst.getCardName().trim();
		sendFocusCardName = n.isEmpty() ? sendFocusCardName : n;
		sendPickFromVariantOnly = true;
		updateSouthBarButtons();
	}

	private void onAlbumDoubleClickOffer(AlbumSlot slot)
	{
		if (!cardPartyTradeService.isTradeActive() || slot == null || !slot.ownedAny())
		{
			return;
		}
		if (slot.soleInstanceId() != null)
		{
			offerInstanceForTrade(slot.soleInstanceId());
			return;
		}
		if (slot.totalOwnedQty() > 1 && slot.card() != null)
		{
			onOwnedMultiCopyAlbumPress(-1, slot);
		}
	}

	private void onVariantDoubleClickOffer(OwnedCardInstance inst)
	{
		if (!cardPartyTradeService.isTradeActive() || inst == null)
		{
			return;
		}
		onVariantInstancePicked(inst);
		offerInstanceForTrade(inst.getInstanceId());
	}

	private void offerInstanceForTrade(String instanceId)
	{
		if (instanceId == null || instanceId.isEmpty())
		{
			return;
		}
		boolean locked = stateService.getState().getCollectionState().findInstanceById(instanceId)
			.map(OwnedCardInstance::isLocked)
			.orElse(false);
		if (locked)
		{
			sendStatusLabel.setText(LOCKED_CARD_ACTION_TOOLTIP);
			return;
		}
		String err = cardPartyTradeService.offerCard(instanceId);
		if (err != null)
		{
			sendStatusLabel.setText(err);
		}
		else
		{
			sendStatusLabel.setText("");
			sendChosenInstanceId = instanceId;
			stateService.getState().getCollectionState().findInstanceById(instanceId).ifPresent(inst ->
			{
				String n = inst.getCardName() == null ? "" : inst.getCardName().trim();
				if (!n.isEmpty())
				{
					sendFocusCardName = n;
				}
			});
		}
		refreshPartyTradeUi();
	}

	private void onAlbumCardLockToggle(AlbumSlot slot)
	{
		if (slot == null || slot.soleInstanceId() == null)
		{
			return;
		}
		if (stateService.toggleCardInstanceLock(slot.soleInstanceId()))
		{
			refreshCurrentPage();
			updateSouthBarButtons();
		}
	}

	private void onVariantCardLockToggle(OwnedCardInstance inst)
	{
		if (inst == null)
		{
			return;
		}
		if (stateService.toggleCardInstanceLock(inst.getInstanceId()))
		{
			refreshActiveVariantCopies();
		}
	}

	private void refreshActiveVariantCopies()
	{
		if (!albumVariantsVisible || sendFocusCardName == null)
		{
			return;
		}
		String cardName = sendFocusCardName.trim();
		if (cardName.isEmpty())
		{
			return;
		}
		List<OwnedCardInstance> copies = new ArrayList<>(
			stateService.getState().getCollectionState().instancesForCardName(cardName));
		if (copies.size() < 2)
		{
			exitAlbumVariantView();
			rebuildModel();
			return;
		}
		copies.sort(Comparator.comparing(OwnedCardInstance::isFoil).reversed()
			.thenComparingLong(OwnedCardInstance::getPulledAtEpochMs));
		Color rarity = rarityTable.colorForCardName(cardName);
		CardDefinition def = cardDefinitionForName(cardName);
		rememberVariantImageUrl(def);
		variantsPanel.setVariants(def, rarity, copies, sendChosenInstanceId);
		updateSouthBarButtons();
	}

	private void refreshPartyMemberCombo()
	{
		int prevSel = partyMemberCombo.getSelectedIndex();
		Long prevId = prevSel >= 0 && prevSel < partyMemberIds.size() ? partyMemberIds.get(prevSel) : null;

		partyMemberIds.clear();
		partyMemberCombo.removeAllItems();
		partyMemberCombo.addItem("— Select party member —");
		partyMemberIds.add(-1L);

		boolean inParty = partyService.isInParty();
		PartyMember local = partyService.getLocalMember();
		boolean hasOther = false;
		if (inParty && local != null)
		{
			for (PartyMember m : partyService.getMembers())
			{
				if (m == null || m.getMemberId() == local.getMemberId())
				{
					continue;
				}
				String dn = m.getDisplayName();
				String trimmedDn = dn == null ? "" : Text.removeTags(dn).trim();
				if (trimmedDn.equalsIgnoreCase("<unknown>"))
				{
					continue;
				}
				if (trimmedDn.isEmpty())
				{
					continue;
				}
				if (trimmedDn.regionMatches(true, 0, "Member #", 0, "Member #".length()))
				{
					continue;
				}
				hasOther = true;
				partyMemberCombo.addItem(trimmedDn);
				partyMemberIds.add(m.getMemberId());
			}
		}

		boolean partyTradeReady = inParty && local != null && hasOther;
		partyMemberCombo.setEnabled(partyTradeReady);
		partyMemberCombo.setToolTipText(partyTradeReady ? null : PARTY_SEND_TOOLTIP);
		sendCardBtn.setToolTipText(partyTradeReady ? null : PARTY_SEND_TOOLTIP);

		if (prevId != null)
		{
			for (int i = 0; i < partyMemberIds.size(); i++)
			{
				if (prevId.equals(partyMemberIds.get(i)))
				{
					partyMemberCombo.setSelectedIndex(i);
					updateSouthBarButtons();
					return;
				}
			}
		}
		partyMemberCombo.setSelectedIndex(0);
		updateSouthBarButtons();
	}

	private void onOwnedMultiCopyAlbumPress(int slotIndex, AlbumSlot slot)
	{
		enterAlbumVariantView(slot);
	}

	private void onSlotSelectionChanged()
	{
		AlbumSlot slot = grid.getSelectedSlot();
		if (slot == null || !slot.ownedAny())
		{
			if (!sendPickFromVariantOnly)
			{
				sendChosenInstanceId = null;
				sendFocusCardName = null;
			}
			updateSouthBarButtons();
			return;
		}
		sendPickFromVariantOnly = false;
		String newName = slot.card() == null ? null : slot.card().getName();
		if (sendFocusCardName != null && newName != null && !Objects.equals(sendFocusCardName, newName))
		{
			sendChosenInstanceId = null;
		}
		sendFocusCardName = newName;
		if (newName != null)
		{
			List<OwnedCardInstance> row = stateService.getState().getCollectionState().instancesForCardName(newName);
			if (row.size() == 1)
			{
				sendChosenInstanceId = row.get(0).getInstanceId();
			}
			else if (row.size() > 1)
			{
				boolean idMatchesCard = sendChosenInstanceId != null
					&& stateService.getState().getCollectionState().findInstanceById(sendChosenInstanceId)
						.filter(i -> newName.equals(i.getCardName()))
						.isPresent();
				if (!idMatchesCard)
				{
					sendChosenInstanceId = null;
				}
			}
		}
		updateSouthBarButtons();
	}

	void refreshPartyTradeUi()
	{
		refreshPartyTradeChrome();
		if (albumVariantsVisible)
		{
			refreshActiveVariantCopies();
		}
		else
		{
			refreshCurrentPage();
		}
	}

	/**
	 * Updates trade invite/status/south-bar chrome without rebuilding album slots.
	 * Used on open and the party poll timer so idle album viewing does not repaint the grid every 2s.
	 */
	private void refreshPartyTradeChrome()
	{
		String statusHint = cardPartyTradeService.consumePendingStatusMessage();
		if (statusHint != null && !statusHint.isEmpty())
		{
			showTemporaryStatus(statusHint);
		}

		CardPartyTradeService.PendingInboundInvite invite = cardPartyTradeService.getPendingInboundInvite();
		if (invite != null)
		{
			acceptTradeBtn.setText("Accept trade request from " + invite.getFromDisplayName());
			acceptTradeBtn.setVisible(true);
		}
		else
		{
			acceptTradeBtn.setVisible(false);
		}
		variantsPanel.setOfferedInstancePredicate(cardPartyTradeService::isInstanceOfferedLocally);
		updateSouthBarButtons();
		if (acceptTradeBtn.getParent() != null)
		{
			acceptTradeBtn.getParent().revalidate();
			acceptTradeBtn.getParent().repaint();
		}
	}

	private void showTemporaryStatus(String message)
	{
		if (message == null || message.isEmpty())
		{
			return;
		}
		sendStatusLabel.setText(message);
		if (statusFlashTimer == null)
		{
			statusFlashTimer = new Timer(10_000, e ->
			{
				String current = sendStatusLabel.getText();
				if (current != null && !current.isBlank())
				{
					sendStatusLabel.setText(" ");
				}
			});
			statusFlashTimer.setRepeats(false);
		}
		statusFlashTimer.restart();
	}

	private void updateSouthBarButtons()
	{
		boolean partyReady = partyMemberCombo.isEnabled();
		int pi = partyMemberCombo.getSelectedIndex();
		boolean recipientOk = partyReady && pi > 0 && pi < partyMemberIds.size()
			&& partyMemberIds.get(pi) != null && partyMemberIds.get(pi) != -1L;
		AlbumSlot slot = grid.getSelectedSlot();
		boolean gridSlotOk = slot != null && slot.ownedAny();
		boolean variantSendOk = sendPickFromVariantOnly
			&& sendChosenInstanceId != null && !sendChosenInstanceId.isEmpty()
			&& sendFocusCardName != null && !sendFocusCardName.trim().isEmpty();
		boolean selectionOk = gridSlotOk || variantSendOk;
		boolean idOk = sendChosenInstanceId != null && !sendChosenInstanceId.isEmpty()
			&& stateService.getState().getCollectionState().findInstanceById(sendChosenInstanceId).isPresent();
		boolean locked = isChosenInstanceLocked();
		boolean tradeActive = cardPartyTradeService.isTradeActive();
		boolean outboundPending = cardPartyTradeService.hasPendingOutboundInvite();
		boolean tradeBusy = cardPartyTradeService.isBusy();
		boolean inviteCooldown = cardPartyTradeService.isTradeInviteOnCooldown();
		long cooldownMs = cardPartyTradeService.getTradeInviteCooldownRemainingMs();

		if (outboundPending)
		{
			sendTradeOfferBtn.setText("Cancel trade offer");
			sendTradeOfferBtn.setEnabled(true);
			sendTradeOfferBtn.setToolTipText("Cancel the pending trade request.");
		}
		else
		{
			sendTradeOfferBtn.setText("Send a trade offer");
			boolean canInvite = recipientOk && !tradeBusy && !inviteCooldown;
			sendTradeOfferBtn.setEnabled(canInvite);
			if (!recipientOk)
			{
				sendTradeOfferBtn.setToolTipText(PARTY_SEND_TOOLTIP);
			}
			else if (tradeBusy)
			{
				sendTradeOfferBtn.setToolTipText("Finish or cancel your current trade first.");
			}
			else if (inviteCooldown)
			{
				long secs = Math.max(1L, (cooldownMs + 999L) / 1000L);
				sendTradeOfferBtn.setToolTipText("Wait " + secs + "s before sending another trade request.");
			}
			else
			{
				sendTradeOfferBtn.setToolTipText(null);
			}
		}

		offerForTradeBtn.setVisible(tradeActive);
		if (!tradeActive)
		{
			offerForTradeBtn.setEnabled(false);
		}

		if (!selectionOk || !idOk)
		{
			sendCardBtn.setEnabled(false);
			offerForTradeBtn.setEnabled(false);
			sellCardBtn.setEnabled(false);
			sellCardBtn.setText("Sell");
			syncSouthBarButtonHeights();
			return;
		}

		if (locked)
		{
			sendCardBtn.setEnabled(false);
			sendCardBtn.setToolTipText(LOCKED_CARD_ACTION_TOOLTIP);
			offerForTradeBtn.setEnabled(false);
			offerForTradeBtn.setToolTipText(LOCKED_CARD_ACTION_TOOLTIP);
			sellCardBtn.setText("Sell");
			sellCardBtn.setEnabled(false);
			sellCardBtn.setToolTipText(LOCKED_CARD_ACTION_TOOLTIP);
			syncSouthBarButtonHeights();
			return;
		}

		sendCardBtn.setEnabled(recipientOk && !tradeActive);
		sendCardBtn.setToolTipText(recipientOk ? null : PARTY_SEND_TOOLTIP);

		if (tradeActive)
		{
			boolean alreadyOffered = cardPartyTradeService.isInstanceOfferedLocally(sendChosenInstanceId);
			boolean giftPending = cardPartyTransferService.isInstancePendingGift(sendChosenInstanceId);
			offerForTradeBtn.setEnabled(!alreadyOffered && !giftPending);
			offerForTradeBtn.setToolTipText(alreadyOffered
				? "That card is already in your trade offer."
				: (giftPending ? "That card copy is already being sent." : null));
		}

		long sellValue = sellCreditsForChosenInstance();
		sellCardBtn.setText("Sell for " + NumberFormatting.format(sellValue));
		sellCardBtn.setEnabled(true);
		sellCardBtn.setToolTipText(null);
		syncSouthBarButtonHeights();
	}

	private boolean isChosenInstanceLocked()
	{
		if (sendChosenInstanceId == null || sendChosenInstanceId.isEmpty())
		{
			return false;
		}
		return stateService.getState().getCollectionState().findInstanceById(sendChosenInstanceId)
			.map(OwnedCardInstance::isLocked)
			.orElse(false);
	}

	private long sellCreditsForChosenInstance()
	{
		if (sendChosenInstanceId == null || sendChosenInstanceId.isEmpty())
		{
			return 0L;
		}
		return stateService.getState().getCollectionState().findInstanceById(sendChosenInstanceId)
			.map(inst -> DuplicateSellCredits.creditsForCard(
				cardDefinitionForName(inst.getCardName()), inst.isFoil()))
			.orElse(0L);
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

	private void onSellSelectedCardClicked(ActionEvent e)
	{
		sellSelectedCard();
	}

	private void sellSelectedCard()
	{
		if (sendChosenInstanceId == null || sendChosenInstanceId.isEmpty())
		{
			return;
		}
		if (isChosenInstanceLocked())
		{
			return;
		}
		long credits = sellCreditsForChosenInstance();
		if (credits <= 0L)
		{
			return;
		}
		if (isOnlyOwnedCopy(sendChosenInstanceId))
		{
			String cardName = displayNameForInstance(sendChosenInstanceId);
			int choice = JOptionPane.showConfirmDialog(
				this,
				"Are you sure you want to sell your only " + cardName + " for "
					+ NumberFormatting.format(credits) + " credits?",
				"Sell card",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION)
			{
				return;
			}
		}
		String instanceId = sendChosenInstanceId;
		if (!stateService.removeCardInstance(instanceId))
		{
			return;
		}
		stateService.addCredits(credits);
		sendChosenInstanceId = null;
		sendPickFromVariantOnly = false;
		sendFocusCardName = null;
		sendStatusLabel.setText("");
		rebuildModel();
		if (sidebarRefresh != null)
		{
			sidebarRefresh.run();
		}
	}

	private boolean isOnlyOwnedCopy(String instanceId)
	{
		return stateService.getState().getCollectionState().findInstanceById(instanceId)
			.map(inst ->
			{
				String name = inst.getCardName();
				if (name == null)
				{
					return false;
				}
				String n = name.trim();
				return !n.isEmpty()
					&& stateService.getState().getCollectionState().instancesForCardName(n).size() == 1;
			})
			.orElse(false);
	}

	private String displayNameForInstance(String instanceId)
	{
		return stateService.getState().getCollectionState().findInstanceById(instanceId)
			.map(inst -> TcgPluginGameMessages.announcedCardLabel(inst.getCardName(), inst.isFoil()))
			.orElse(sendFocusCardName == null ? "card" : sendFocusCardName.trim());
	}

	private void onSendToPartyClicked(ActionEvent e)
	{
		int pi = partyMemberCombo.getSelectedIndex();
		if (pi <= 0 || pi >= partyMemberIds.size())
		{
			return;
		}
		if (!sendPickFromVariantOnly)
		{
			AlbumSlot slot = grid.getSelectedSlot();
			if (slot == null || !slot.ownedAny())
			{
				return;
			}
		}
		if (sendChosenInstanceId == null || sendChosenInstanceId.isEmpty())
		{
			return;
		}
		if (isChosenInstanceLocked())
		{
			sendStatusLabel.setText(LOCKED_CARD_ACTION_TOOLTIP);
			return;
		}
		sendCardBtn.setEnabled(false);
		long recipientId = partyMemberIds.get(pi);
		String instanceId = sendChosenInstanceId;
		String err = cardPartyTransferService.sendGift(recipientId, instanceId);
		if (err != null)
		{
			sendStatusLabel.setText(err);
			updateSouthBarButtons();
		}
		else
		{
			sendStatusLabel.setText("");
			sendChosenInstanceId = null;
			sendPickFromVariantOnly = false;
			rebuildModel();
		}
	}

	private void onSendTradeOfferClicked(ActionEvent e)
	{
		if (cardPartyTradeService.hasPendingOutboundInvite())
		{
			String err = cardPartyTradeService.cancelPendingOutboundInvite();
			if (err != null)
			{
				sendStatusLabel.setText(err);
			}
			else
			{
				showTemporaryStatus("Trade offer cancelled.");
			}
			refreshPartyTradeUi();
			return;
		}

		int pi = partyMemberCombo.getSelectedIndex();
		if (pi <= 0 || pi >= partyMemberIds.size())
		{
			return;
		}
		long recipientId = partyMemberIds.get(pi);
		String recipientName = String.valueOf(partyMemberCombo.getSelectedItem());
		String err = cardPartyTradeService.sendTradeInvite(recipientId);
		if (err != null)
		{
			sendStatusLabel.setText(err);
		}
		else
		{
			CardPartyTradeService.PendingOutboundInviteView outbound =
				cardPartyTradeService.getPendingOutboundInvite();
			String who = outbound != null ? outbound.getRecipientDisplayName() : recipientName;
			showTemporaryStatus("Trade request sent to " + who + ".");
		}
		refreshPartyTradeUi();
	}

	private void onAcceptTradeClicked()
	{
		String err = cardPartyTradeService.acceptPendingInvite();
		if (err != null)
		{
			sendStatusLabel.setText(err);
		}
		else
		{
			sendStatusLabel.setText("");
		}
		refreshPartyTradeUi();
	}

	private void onOfferForTradeClicked()
	{
		if (sendChosenInstanceId == null || sendChosenInstanceId.isEmpty())
		{
			return;
		}
		offerInstanceForTrade(sendChosenInstanceId);
	}

	private static final class ModelRebuildInputs
	{
		private final int collectionIdx;
		private final List<CardDefinition> allCards;
		private final List<TabFilter> tabFilters;
		private final AlbumRarityTable rarityTable;
		private final String rarityPick;
		private final String searchQuery;
		private final Map<CardCollectionKey, Integer> owned;
		private final Map<String, Long> mostRecentPulledAtByName;
		private final boolean foilOnly;
		private final boolean obtainedOnly;
		private final boolean duplicatesOnly;
		private final boolean missingOnly;
		private final AlbumSortMode sortMode;
		private final int preservePageIndex;

		private ModelRebuildInputs(
			int collectionIdx,
			List<CardDefinition> allCards,
			List<TabFilter> tabFilters,
			AlbumRarityTable rarityTable,
			String rarityPick,
			String searchQuery,
			Map<CardCollectionKey, Integer> owned,
			Map<String, Long> mostRecentPulledAtByName,
			boolean foilOnly,
			boolean obtainedOnly,
			boolean duplicatesOnly,
			boolean missingOnly,
			AlbumSortMode sortMode,
			int preservePageIndex)
		{
			this.collectionIdx = collectionIdx;
			this.allCards = allCards;
			this.tabFilters = tabFilters;
			this.rarityTable = rarityTable;
			this.rarityPick = rarityPick;
			this.searchQuery = searchQuery;
			this.owned = owned;
			this.mostRecentPulledAtByName = mostRecentPulledAtByName;
			this.foilOnly = foilOnly;
			this.obtainedOnly = obtainedOnly;
			this.duplicatesOnly = duplicatesOnly;
			this.missingOnly = missingOnly;
			this.sortMode = sortMode;
			this.preservePageIndex = preservePageIndex;
		}
	}

	private static final class TabFilter
	{
		private final String title;
		private final Predicate<CardDefinition> include;

		private TabFilter(String title, Predicate<CardDefinition> include)
		{
			this.title = title;
			this.include = include;
		}

		private String getTitle()
		{
			return title;
		}

		private Predicate<CardDefinition> getInclude()
		{
			return include;
		}
	}
}

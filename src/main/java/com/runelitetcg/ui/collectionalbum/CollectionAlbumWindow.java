package com.runelitetcg.ui.collectionalbum;

import com.runelitetcg.data.BoosterPackDefinition;
import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.data.PackCatalog;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.OwnedCardInstance;
import com.runelitetcg.service.CardPartyTransferService;
import com.runelitetcg.service.DuplicateSellCredits;
import com.runelitetcg.service.TcgStateService;
import com.runelitetcg.service.WikiImageCacheService;
import com.runelitetcg.ui.SharedCardRenderer;
import com.runelitetcg.util.CollectionAlbumWindowSizeUtil;
import com.runelitetcg.util.NumberFormatting;
import com.runelitetcg.util.TcgPluginGameMessages;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
	private static final int PAGE_SIZE = 21;
	private static final String RARITY_FILTER_ALL = "All";
	private static final List<String> RARITY_TIERS_LOW_TO_HIGH = List.of(
		"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic", "Godly");

	private final CardDatabase cardDatabase;
	private final TcgStateService stateService;
	private final PackCatalog packCatalog;
	private final WikiImageCacheService imageCacheService;
	private final PartyService partyService;
	private final CardPartyTransferService cardPartyTransferService;

	private final List<Long> partyMemberIds = new ArrayList<>();
	private final JComboBox<String> partyMemberCombo = new JComboBox<>();
	private final JButton sendCardBtn = new JButton("Send");
	private final JButton sellCardBtn = new JButton("Sell");
	private final JLabel sendStatusLabel = new JLabel(" ");
	private final Timer partyUiTimer;

	private AlbumRarityTable rarityTable = AlbumRarityTable.build(List.of());
	private List<TabFilter> tabFilters = List.of();
	private final JComboBox<String> collectionCombo = new JComboBox<>();
	private boolean suppressCollectionComboEvents;
	private final JTextField searchField = new JTextField(18);
	private final JComboBox<AlbumSortMode> sortCombo = new JComboBox<>(AlbumSortMode.values());
	private final JComboBox<String> rarityCombo = new JComboBox<>();
	private final JRadioButton radCardsAll = new JRadioButton("All cards", true);
	private final JRadioButton radObtained = new JRadioButton("Obtained only");
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
	private final Timer imagePollTimer;
	/** ~30 FPS repaints while foil sheen is visible (album grid is otherwise polled at {@link #imagePollTimer}). */
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

	/** True when {@link #sendChosenInstanceId} was chosen from the variant grid (no album cell selection). */
	private boolean sendPickFromVariantOnly;

	private int pageIndex;
	private int filteredTotal;
	private int pageCount;
	/** Selected collection row for party send; cleared when changing cards or after a successful send. */
	private String sendChosenInstanceId;
	private String sendFocusCardName;

	public CollectionAlbumWindow(
		CardDatabase cardDatabase,
		TcgStateService stateService,
		PackCatalog packCatalog,
		WikiImageCacheService imageCacheService,
		PartyService partyService,
		CardPartyTransferService cardPartyTransferService)
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
		this.grid = new CollectionAlbumGridPanel(imageCacheService, this::onOwnedMultiCopyAlbumPress, this::onSlotSelectionChanged);
		this.variantsPanel = new CollectionAlbumVariantsPanel(imageCacheService, this::onVariantInstancePicked);

		setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(
			CollectionAlbumWindowSizeUtil.MIN_WIDTH,
			CollectionAlbumWindowSizeUtil.MIN_HEIGHT));
		applySavedWindowSize();
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				persistWindowSize();
			}
		});
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
		ownGroup.add(radMissing);
		styleRadio(radCardsAll);
		styleRadio(radObtained);
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
			rebuildModel();
		});
		nextBtn.addActionListener(e ->
		{
			pageIndex = Math.min(Math.max(0, pageCount - 1), pageIndex + 1);
			rebuildModel();
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

		JPanel partyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		partyRow.setOpaque(false);
		JLabel partyLbl = new JLabel("Party:");
		partyLbl.setForeground(Color.WHITE);
		partyRow.add(partyLbl);
		partyMemberCombo.setForeground(Color.WHITE);
		partyMemberCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		partyRow.add(partyMemberCombo);
		sendCardBtn.setFocusable(false);
		sendCardBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		sendCardBtn.setForeground(Color.WHITE);
		partyRow.add(sendCardBtn);
		partyRow.add(sendStatusLabel);

		JPanel sellRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		sellRow.setOpaque(false);
		sellCardBtn.setFocusable(false);
		sellCardBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		sellCardBtn.setForeground(Color.WHITE);
		sellCardBtn.setEnabled(false);
		sellRow.add(sellCardBtn);

		south.add(partyRow, BorderLayout.WEST);
		south.add(sellRow, BorderLayout.EAST);
		partyMemberCombo.addActionListener(e -> updateSouthBarButtons());
		sendCardBtn.addActionListener(this::onSendToPartyClicked);
		sellCardBtn.addActionListener(this::onSellSelectedCardClicked);
		add(south, BorderLayout.SOUTH);

		partyUiTimer = new Timer(2000, e ->
		{
			if (isShowing())
			{
				refreshPartyMemberCombo();
			}
		});
		partyUiTimer.start();

		imagePollTimer = new Timer(140, e ->
		{
			if (isShowing())
			{
				grid.repaint();
				if (albumVariantsVisible)
				{
					variantsPanel.repaint();
				}
			}
		});
		imagePollTimer.start();

		foilAnimTimer = new Timer(33, e ->
		{
			if (!isShowing())
			{
				return;
			}
			if (grid.needsFoilAnimationRepaint())
			{
				grid.repaint();
			}
			if (albumVariantsVisible && variantsPanel.needsFoilAnimationRepaint())
			{
				variantsPanel.repaint();
			}
		});
		foilAnimTimer.start();

		styleFrameFonts();
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
		radMissing.setFont(small);
		foilOnlyCheck.setFont(small);
		partyMemberCombo.setFont(small);
		variantBackToAlbumBtn.setFont(small);
		variantPagingPrevBtn.setFont(small);
		variantPagingNextBtn.setFont(small);
		variantPagingLabel.setFont(small);
		variantCardTitleLbl.setFont(FontManager.getRunescapeBoldFont());
		sendCardBtn.setFont(small);
		sellCardBtn.setFont(small);
		sendStatusLabel.setFont(small);
	}

	private void stopTimers()
	{
		partyUiTimer.stop();
		imagePollTimer.stop();
		foilAnimTimer.stop();
		if (searchDebounceTimer != null)
		{
			searchDebounceTimer.stop();
		}
	}

	void disposeInternal()
	{
		persistWindowSize();
		stopTimers();
		dispose();
	}

	private void applySavedWindowSize()
	{
		var s = stateService.getState();
		Dimension size = CollectionAlbumWindowSizeUtil.resolve(s.getAlbumWindowWidth(), s.getAlbumWindowHeight());
		setSize(size);
	}

	private void persistWindowSize()
	{
		Dimension size = getSize();
		if (size.width <= 0 || size.height <= 0)
		{
			return;
		}
		stateService.setAlbumWindowSize(size.width, size.height);
	}

	public void refreshData()
	{
		cardDatabase.load();
		List<CardDefinition> all = cardDatabase.getCards();
		rarityTable = AlbumRarityTable.build(all);
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
		styleFrameFonts();
		pageIndex = 0;
		rebuildModel();
	}

	private List<TabFilter> buildTabFilters()
	{
		List<TabFilter> out = new ArrayList<>();
		out.add(new TabFilter("All", CollectionAlbumWindow::hasCardName));
		for (BoosterPackDefinition b : packCatalog.getBoosters())
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
			rebuildModel();
		}
		e.consume();
	}

	public void rebuildModel()
	{
		exitAlbumVariantView();
		refreshPartyMemberCombo();
		int collectionIdx = collectionCombo.getSelectedIndex();
		if (tabFilters.isEmpty() || collectionIdx < 0 || collectionIdx >= tabFilters.size())
		{
			grid.setSlots(List.of());
			return;
		}

		Predicate<CardDefinition> tabPred = tabFilters.get(collectionIdx).getInclude();
		List<CardDefinition> working = cardDatabase.getCards().stream()
			.filter(CollectionAlbumWindow::hasCardName)
			.filter(tabPred)
			.collect(Collectors.toCollection(ArrayList::new));

		String rarityPick = (String) rarityCombo.getSelectedItem();
		if (rarityPick != null && !RARITY_FILTER_ALL.equals(rarityPick))
		{
			working.removeIf(c -> !rarityPick.equals(rarityTable.tierLabelForCard(c)));
		}

		String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
		if (!q.isEmpty())
		{
			working.removeIf(c -> !c.getName().toLowerCase(Locale.ROOT).contains(q));
		}

		Map<CardCollectionKey, Integer> owned = stateService.getState().getCollectionState().getOwnedCards();
		Set<String> collected = collectedNamesFromOwned(owned);

		if (foilOnlyCheck.isSelected())
		{
			working.removeIf(c -> !hasFoilOwned(owned, c.getName()));
		}

		if (radObtained.isSelected())
		{
			working.removeIf(c -> !collected.contains(c.getName()));
		}
		else if (radMissing.isSelected())
		{
			working.removeIf(c -> collected.contains(c.getName()));
		}

		AlbumSortMode mode = (AlbumSortMode) sortCombo.getSelectedItem();
		if (mode == null)
		{
			mode = AlbumSortMode.SCORE_DESC;
		}
		Comparator<CardDefinition> byName = Comparator.comparing(
			c -> c.getName() == null ? "" : c.getName(),
			String.CASE_INSENSITIVE_ORDER);
		switch (mode)
		{
			case SCORE_DESC:
				working.sort(Comparator.<CardDefinition>comparingDouble(SharedCardRenderer::cardDisplayScore)
					.reversed()
					.thenComparing(byName));
				break;
			case RARITY_DESC:
				working.sort(Comparator.<CardDefinition>comparingInt(
					c -> tierSortKey(rarityTable.tierLabelForCard(c)))
					.reversed()
					.thenComparing(byName));
				break;
			case NAME_ASC:
			default:
				working.sort(byName);
				break;
		}

		filteredTotal = working.size();
		pageCount = Math.max(1, (filteredTotal + PAGE_SIZE - 1) / PAGE_SIZE);
		pageIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
		int from = pageIndex * PAGE_SIZE;
		int to = Math.min(from + PAGE_SIZE, filteredTotal);

		List<AlbumSlot> slots = new ArrayList<>();
		for (int i = from; i < to; i++)
		{
			CardDefinition c = working.get(i);
			String name = c.getName();
			Color rarity = rarityTable.colorForCardName(name);
			boolean ownAny = collected.contains(name);
			boolean displayFoil = hasFoilOwned(owned, name);
			Integer nf = owned.get(new CardCollectionKey(name, false));
			Integer ff = owned.get(new CardCollectionKey(name, true));
			int nQty = nf == null ? 0 : nf;
			int fQty = ff == null ? 0 : ff;
			String singleTip = singleCopyAlbumHoverTooltip(name, nQty, fQty, ownAny);
			slots.add(new AlbumSlot(c, rarity, ownAny, displayFoil, nQty, fQty, singleTip));
		}
		grid.setSlots(slots);

		int startN = filteredTotal == 0 ? 0 : from + 1;
		int endN = filteredTotal == 0 ? 0 : to;
		pageLabel.setText(String.format("Page %s / %s   (%s–%s of %s)",
			NumberFormatting.format(pageIndex + 1), NumberFormatting.format(pageCount),
			NumberFormatting.format(startN), NumberFormatting.format(endN), NumberFormatting.format(filteredTotal)));
		prevBtn.setEnabled(pageIndex > 0);
		nextBtn.setEnabled(pageIndex < pageCount - 1);

		preloadAround(working, from, to);
	}

	private void preloadAround(List<CardDefinition> ordered, int from, int to)
	{
		List<String> urls = new ArrayList<>();
		int lo = Math.max(0, from - PAGE_SIZE);
		int hi = Math.min(ordered.size(), to + PAGE_SIZE);
		for (int i = lo; i < hi; i++)
		{
			CardDefinition c = ordered.get(i);
			if (c != null && c.getImageUrl() != null && !c.getImageUrl().trim().isEmpty())
			{
				urls.add(c.getImageUrl());
			}
		}
		imageCacheService.preload(urls);
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

	private static boolean hasFoilOwned(Map<CardCollectionKey, Integer> owned, String cardName)
	{
		if (cardName == null)
		{
			return false;
		}
		Integer n = owned.get(new CardCollectionKey(cardName, true));
		return n != null && n > 0;
	}

	private String singleCopyAlbumHoverTooltip(String cardName, int nQty, int fQty, boolean ownAny)
	{
		if (!ownAny || cardName == null || nQty + fQty != 1)
		{
			return null;
		}
		List<OwnedCardInstance> row = stateService.getState().getCollectionState().instancesForCardName(cardName);
		if (row.size() != 1)
		{
			return null;
		}
		return AlbumInstanceTooltip.format(row.get(0));
	}

	private void exitAlbumVariantView()
	{
		albumNorthLayout.show(albumNorthHost, VIEW_NORTH_BROWSE);
		albumCenterLayout.show(albumCenterHost, VIEW_ALBUM_BROWSE);
		albumVariantsVisible = false;
		grid.clearSelection();
		sendChosenInstanceId = null;
		sendFocusCardName = null;
		sendPickFromVariantOnly = false;
		updateSouthBarButtons();
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

		if (!selectionOk || !idOk)
		{
			sendCardBtn.setEnabled(false);
			sellCardBtn.setEnabled(false);
			sellCardBtn.setText("Sell");
			return;
		}

		sendCardBtn.setEnabled(recipientOk && idOk);

		long sellValue = sellCreditsForChosenInstance();
		sellCardBtn.setText("Sell for " + NumberFormatting.format(sellValue));
		sellCardBtn.setEnabled(true);
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
		if (sendChosenInstanceId == null || sendChosenInstanceId.isEmpty())
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
		long recipientId = partyMemberIds.get(pi);
		String err = cardPartyTransferService.sendGift(recipientId, sendChosenInstanceId);
		if (err != null)
		{
			sendStatusLabel.setText(err);
		}
		else
		{
			sendStatusLabel.setText("");
			sendChosenInstanceId = null;
			sendPickFromVariantOnly = false;
			rebuildModel();
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

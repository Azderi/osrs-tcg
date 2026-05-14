package com.runelitetcg.ui.collectionalbum;

import com.runelitetcg.data.BoosterPackDefinition;
import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.data.PackCatalog;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.service.CardPartyTransferService;
import com.runelitetcg.service.TcgStateService;
import com.runelitetcg.service.WikiImageCacheService;
import com.runelitetcg.ui.SharedCardRenderer;
import com.runelitetcg.util.NumberFormatting;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
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

public final class CollectionAlbumWindow extends JFrame
{
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
	private final JLabel variantLbl = new JLabel("Variant:");
	private final JComboBox<VariantChoice> variantCombo = new JComboBox<>();
	private final JButton sendCardBtn = new JButton("Send");
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
	private Timer searchDebounceTimer;
	private final Timer imagePollTimer;

	private int pageIndex;
	private int filteredTotal;
	private int pageCount;
	/** Identity of the slot used for normal/foil send combo; when unchanged, foil preference survives party/timer refresh. */
	private String sendVariantSlotKey;
	private boolean sendPreferFoilVariant;

	public CollectionAlbumWindow(
		CardDatabase cardDatabase,
		TcgStateService stateService,
		PackCatalog packCatalog,
		WikiImageCacheService imageCacheService,
		PartyService partyService,
		CardPartyTransferService cardPartyTransferService)
	{
		super("OSRS TCG — Collection album");
		this.cardDatabase = cardDatabase;
		this.stateService = stateService;
		this.packCatalog = packCatalog;
		this.imageCacheService = imageCacheService;
		this.partyService = partyService;
		this.cardPartyTransferService = cardPartyTransferService;
		this.grid = new CollectionAlbumGridPanel(imageCacheService, this::onSlotSelectionChanged);

		setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(1300, 810));
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
		add(top, BorderLayout.NORTH);
		grid.setBorder(BorderFactory.createEmptyBorder(4, 6, 12, 6));
		grid.addMouseWheelListener(pageWheel);
		add(grid, BorderLayout.CENTER);

		JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		south.setOpaque(false);
		south.setBorder(new EmptyBorder(0, 8, 6, 8));
		JLabel partyLbl = new JLabel("Party:");
		partyLbl.setForeground(Color.WHITE);
		south.add(partyLbl);
		partyMemberCombo.setForeground(Color.WHITE);
		partyMemberCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		south.add(partyMemberCombo);
		variantLbl.setForeground(Color.WHITE);
		variantLbl.setVisible(false);
		variantCombo.setForeground(Color.WHITE);
		variantCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		variantCombo.setVisible(false);
		south.add(variantLbl);
		south.add(variantCombo);
		sendCardBtn.setFocusable(false);
		sendCardBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		sendCardBtn.setForeground(Color.WHITE);
		south.add(sendCardBtn);
		south.add(sendStatusLabel);
		partyMemberCombo.addActionListener(e -> updateSendButtonState());
		variantCombo.addActionListener(e ->
		{
			if (variantCombo.getItemCount() >= 2 && variantCombo.getSelectedIndex() >= 0)
			{
				sendPreferFoilVariant = variantCombo.getSelectedIndex() == 1;
			}
			updateSendButtonState();
		});
		sendCardBtn.addActionListener(this::onSendToPartyClicked);
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
			}
		});
		imagePollTimer.start();

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
		variantCombo.setFont(small);
		sendCardBtn.setFont(small);
		sendStatusLabel.setFont(small);
	}

	private void stopTimers()
	{
		partyUiTimer.stop();
		imagePollTimer.stop();
		if (searchDebounceTimer != null)
		{
			searchDebounceTimer.stop();
		}
	}

	void disposeInternal()
	{
		stopTimers();
		dispose();
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
			slots.add(new AlbumSlot(c, rarity, ownAny, displayFoil, nQty, fQty));
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
				hasOther = true;
				String dn = m.getDisplayName();
				String label = dn == null || dn.trim().isEmpty() ? ("Member #" + m.getMemberId()) : dn.trim();
				partyMemberCombo.addItem(label);
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
					onSlotSelectionChanged();
					return;
				}
			}
		}
		partyMemberCombo.setSelectedIndex(0);
		onSlotSelectionChanged();
	}

	private void onSlotSelectionChanged()
	{
		variantCombo.removeAllItems();
		if (!partyMemberCombo.isEnabled())
		{
			sendVariantSlotKey = null;
			sendPreferFoilVariant = false;
			variantLbl.setVisible(false);
			variantCombo.setVisible(false);
			variantCombo.setEnabled(false);
			updateSendButtonState();
			return;
		}
		AlbumSlot slot = grid.getSelectedSlot();
		if (slot == null || !slot.ownedAny())
		{
			sendVariantSlotKey = null;
			sendPreferFoilVariant = false;
			variantLbl.setVisible(false);
			variantCombo.setVisible(false);
			variantCombo.setEnabled(false);
			updateSendButtonState();
			return;
		}
		int nf = slot.nonFoilQty();
		int ff = slot.foilQty();
		String name = slot.card() == null ? "" : slot.card().getName();
		String slotKey = name + '\u0001' + nf + '\u0001' + ff;
		boolean both = nf > 0 && ff > 0;
		if (both)
		{
			if (!Objects.equals(slotKey, sendVariantSlotKey))
			{
				sendVariantSlotKey = slotKey;
				sendPreferFoilVariant = false;
			}
			variantLbl.setVisible(true);
			variantCombo.setVisible(true);
			variantCombo.addItem(new VariantChoice(false, nf + "x normal"));
			variantCombo.addItem(new VariantChoice(true, ff + "x foil"));
			variantCombo.setEnabled(true);
			variantCombo.setSelectedIndex(sendPreferFoilVariant ? 1 : 0);
		}
		else
		{
			sendVariantSlotKey = slotKey;
			sendPreferFoilVariant = false;
			variantLbl.setVisible(false);
			variantCombo.setVisible(false);
			variantCombo.setEnabled(false);
		}
		updateSendButtonState();
	}

	private void updateSendButtonState()
	{
		boolean partyReady = partyMemberCombo.isEnabled();
		int pi = partyMemberCombo.getSelectedIndex();
		boolean recipientOk = partyReady && pi > 0 && pi < partyMemberIds.size()
			&& partyMemberIds.get(pi) != null && partyMemberIds.get(pi) != -1L;
		AlbumSlot slot = grid.getSelectedSlot();
		if (slot == null || !slot.ownedAny())
		{
			sendCardBtn.setEnabled(false);
			return;
		}
		int nf = slot.nonFoilQty();
		int ff = slot.foilQty();
		boolean both = nf > 0 && ff > 0;
		boolean variantOk = !both || variantCombo.getSelectedItem() != null;
		boolean hasSendableCopy = nf > 0 || ff > 0;
		sendCardBtn.setEnabled(recipientOk && hasSendableCopy && variantOk);
	}

	private void onSendToPartyClicked(ActionEvent e)
	{
		AlbumSlot slot = grid.getSelectedSlot();
		int pi = partyMemberCombo.getSelectedIndex();
		if (slot == null || !slot.ownedAny() || pi <= 0 || pi >= partyMemberIds.size())
		{
			return;
		}
		int nf = slot.nonFoilQty();
		int ff = slot.foilQty();
		boolean foil;
		if (nf > 0 && ff > 0)
		{
			VariantChoice vc = (VariantChoice) variantCombo.getSelectedItem();
			if (vc == null)
			{
				return;
			}
			foil = vc.isFoil();
		}
		else if (ff > 0)
		{
			foil = true;
		}
		else
		{
			foil = false;
		}
		long recipientId = partyMemberIds.get(pi);
		String name = slot.card() == null ? null : slot.card().getName();
		String err = cardPartyTransferService.sendGift(recipientId, name, foil);
		if (err != null)
		{
			sendStatusLabel.setText(err);
		}
		else
		{
			sendStatusLabel.setText("Offer sent — your copy is removed when they accept.");
			rebuildModel();
		}
	}

	private static final class VariantChoice
	{
		private final boolean foil;
		private final String label;

		private VariantChoice(boolean foil, String label)
		{
			this.foil = foil;
			this.label = label;
		}

		private boolean isFoil()
		{
			return foil;
		}

		@Override
		public String toString()
		{
			return label;
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

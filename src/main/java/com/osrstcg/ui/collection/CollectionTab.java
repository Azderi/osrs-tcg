package com.osrstcg.ui.collection;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.CollectionState;
import com.osrstcg.ui.layout.PackCloseSnapshot;
import com.osrstcg.ui.layout.SidebarLayout;
import com.osrstcg.ui.shop.ShopProgress;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

@Slf4j
public final class CollectionTab
{
	public static final String LIST_CARD = "list";
	public static final String EMPTY_CARD = "empty";
	public static final int ROW_HEIGHT = 24;

	private final CardDatabase cardDatabase;
	private final PackCatalogService packCatalogService;
	private final ScheduledExecutorService scheduler;
	private final IntSupplier contentWidth;
	private final Supplier<PackCloseSnapshot> snapshotSupplier;
	private final Runnable onRendered;
	private final Supplier<Boolean> isActive;
	private final AtomicLong buildGen = new AtomicLong();

	private final JPanel collectionContent;
	private final JPanel collectionListHost;
	private final JList<CollectionListModel.Row> collectionList;
	private final JScrollPane collectionListScrollPane;
	private final JLabel collectionEmptyLabel;
	private final JTextField collectionSearchField;

	private String collectionPackFilterId;
	private RarityMath.Tier collectionRarityFilter;
	private CollectionListModel.SortMode collectionSortMode = CollectionListModel.SortMode.SCORE_DESC;
	private String collectionSearchQuery = "";

	public CollectionTab(
		CardDatabase cardDatabase,
		PackCatalogService packCatalogService,
		ScheduledExecutorService scheduler,
		IntSupplier contentWidth,
		Supplier<PackCloseSnapshot> snapshotSupplier,
		Runnable onRendered,
		Supplier<Boolean> isActive,
		JPanel collectionContent,
		JPanel collectionListHost,
		JList<CollectionListModel.Row> collectionList,
		JScrollPane collectionListScrollPane,
		JLabel collectionEmptyLabel)
	{
		this.cardDatabase = cardDatabase;
		this.packCatalogService = packCatalogService;
		this.scheduler = scheduler;
		this.contentWidth = contentWidth;
		this.snapshotSupplier = snapshotSupplier;
		this.onRendered = onRendered;
		this.isActive = isActive;
		this.collectionContent = collectionContent;
		this.collectionListHost = collectionListHost;
		this.collectionList = collectionList;
		this.collectionListScrollPane = collectionListScrollPane;
		this.collectionEmptyLabel = collectionEmptyLabel;
		this.collectionSearchField = createCollectionSearchField();
	}

	public void configureList()
	{
		collectionEmptyLabel.setForeground(new Color(0xAAAAAA));
		collectionEmptyLabel.setFont(FontManager.getRunescapeSmallFont());
		collectionEmptyLabel.setBorder(new EmptyBorder(4, 2, 0, 2));

		collectionList.setOpaque(true);
		collectionList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		collectionList.setFixedCellHeight(ROW_HEIGHT);
		collectionList.setCellRenderer(new CollectionRowRenderer(contentWidth));
		collectionList.setSelectionModel(new DefaultListSelectionModel()
		{
			@Override
			public void setSelectionInterval(int index0, int index1)
			{
			}

			@Override
			public void addSelectionInterval(int index0, int index1)
			{
			}
		});
		collectionList.setFocusable(false);
		collectionList.setVisibleRowCount(8);
		syncCellWidth();
		collectionListScrollPane.addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				syncCellWidth();
			}
		});
	}

	public void cancelPendingRebuilds()
	{
		buildGen.incrementAndGet();
	}

	public void clearList()
	{
		collectionList.setListData(new CollectionListModel.Row[0]);
	}

	public void syncCellWidth()
	{
		int w = contentWidth.getAsInt();
		if (collectionList.getFixedCellWidth() != w)
		{
			collectionList.setFixedCellWidth(w);
		}
	}

	public void render()
	{
		PackCloseSnapshot snap = snapshotSupplier.get();
		List<CardDefinition> allCards = cardDatabase.getCards();
		List<BoosterPackDefinition> packs = collectionFilterPacks();
		BoosterPackDefinition selectedPack = findPackById(packs, collectionPackFilterId);
		if (collectionPackFilterId != null && selectedPack == null)
		{
			collectionPackFilterId = null;
			selectedPack = null;
		}

		collectionContent.removeAll();
		JPanel toolbar = buildCollectionToolbar(packs, selectedPack);
		collectionContent.add(toolbar, BorderLayout.NORTH);
		collectionContent.add(collectionListHost, BorderLayout.CENTER);
		collectionContent.revalidate();
		collectionContent.repaint();

		scheduleCollectionListRebuild(snap, allCards, selectedPack);
	}

	private void scheduleCollectionListRebuildFromCurrentFilters()
	{
		PackCloseSnapshot snap = snapshotSupplier.get();
		List<CardDefinition> allCards = cardDatabase.getCards();
		List<BoosterPackDefinition> packs = collectionFilterPacks();
		BoosterPackDefinition selectedPack = findPackById(packs, collectionPackFilterId);
		if (collectionPackFilterId != null && selectedPack == null)
		{
			collectionPackFilterId = null;
			selectedPack = null;
		}
		scheduleCollectionListRebuild(snap, allCards, selectedPack);
	}

	private void scheduleCollectionListRebuild(
		PackCloseSnapshot snap,
		List<CardDefinition> allCards,
		BoosterPackDefinition packFilter)
	{
		CollectionState collection = snap.collectionState;
		RarityMath.Tier rarityFilter = collectionRarityFilter;
		CollectionListModel.SortMode sortMode = collectionSortMode;
		String searchQuery = collectionSearchQuery;
		long gen = buildGen.incrementAndGet();

		scheduler.execute(() ->
		{
			try
			{
				List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(allCards);
				Set<String> packEligible = packFilter == null
					? null
					: CollectionListModel.eligibleNamesForPack(packFilter, allCards, rollPool);
				List<CollectionListModel.Row> rows = CollectionListModel.buildRows(
					collection,
					CollectionListModel.indexByLowerName(allCards),
					packEligible,
					rarityFilter,
					searchQuery,
					sortMode);
				SwingUtilities.invokeLater(() -> applyCollectionRows(gen, rows));
			}
			catch (Exception ex)
			{
				log.warn("Collection list build failed", ex);
				SwingUtilities.invokeLater(() ->
				{
					if (gen == buildGen.get())
					{
						applyCollectionRows(gen, List.of());
					}
				});
			}
		});
	}

	private void applyCollectionRows(long gen, List<CollectionListModel.Row> rows)
	{
		if (gen != buildGen.get())
		{
			return;
		}

		CardLayout cards = (CardLayout) collectionListHost.getLayout();
		if (rows == null || rows.isEmpty())
		{
			collectionList.setListData(new CollectionListModel.Row[0]);
			cards.show(collectionListHost, EMPTY_CARD);
		}
		else
		{
			collectionList.setListData(rows.toArray(new CollectionListModel.Row[0]));
			cards.show(collectionListHost, LIST_CARD);
		}
		syncCellWidth();
		collectionListHost.revalidate();
		collectionListHost.repaint();
	}

	private JPanel buildCollectionToolbar(List<BoosterPackDefinition> packs, BoosterPackDefinition selectedPack)
	{
		JPanel toolbar = new JPanel();
		toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
		toolbar.setOpaque(false);
		toolbar.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel filters = new JPanel(new GridLayout(4, 1, 0, 4));
		filters.setOpaque(false);

		if (collectionSearchField.getParent() != null)
		{
			collectionSearchField.getParent().remove(collectionSearchField);
		}
		filters.add(labeledCollectionFilter("Search", collectionSearchField));

		DefaultComboBoxModel<CollectionFilterOptions.PackFilterOption> packModel = new DefaultComboBoxModel<>();
		packModel.addElement(CollectionFilterOptions.PackFilterOption.all());
		CollectionFilterOptions.PackFilterOption selectedPackOption = CollectionFilterOptions.PackFilterOption.all();
		for (BoosterPackDefinition pack : packs)
		{
			if (pack == null)
			{
				continue;
			}
			CollectionFilterOptions.PackFilterOption option = CollectionFilterOptions.PackFilterOption.of(pack);
			packModel.addElement(option);
			if (selectedPack != null && selectedPack.getId() != null
				&& selectedPack.getId().equals(pack.getId()))
			{
				selectedPackOption = option;
			}
		}
		JComboBox<CollectionFilterOptions.PackFilterOption> packCombo = styleCollectionCombo(new JComboBox<>(packModel));
		packCombo.setSelectedItem(selectedPackOption);
		packCombo.addActionListener(e ->
		{
			CollectionFilterOptions.PackFilterOption opt =
				(CollectionFilterOptions.PackFilterOption) packCombo.getSelectedItem();
			String nextId = opt == null ? null : opt.getPackId();
			if ((nextId == null && collectionPackFilterId == null)
				|| (nextId != null && nextId.equals(collectionPackFilterId)))
			{
				return;
			}
			collectionPackFilterId = nextId;
			refreshCollectionTabUi();
		});
		filters.add(labeledCollectionFilter("Collection", packCombo));

		DefaultComboBoxModel<CollectionFilterOptions.RarityFilterOption> rarityModel = new DefaultComboBoxModel<>();
		rarityModel.addElement(CollectionFilterOptions.RarityFilterOption.all());
		CollectionFilterOptions.RarityFilterOption selectedRarity = CollectionFilterOptions.RarityFilterOption.all();
		for (RarityMath.Tier tier : RarityMath.Tier.values())
		{
			CollectionFilterOptions.RarityFilterOption option = CollectionFilterOptions.RarityFilterOption.of(tier);
			rarityModel.addElement(option);
			if (collectionRarityFilter == tier)
			{
				selectedRarity = option;
			}
		}
		JComboBox<CollectionFilterOptions.RarityFilterOption> rarityCombo = styleCollectionCombo(new JComboBox<>(rarityModel));
		rarityCombo.setSelectedItem(selectedRarity);
		rarityCombo.addActionListener(e ->
		{
			CollectionFilterOptions.RarityFilterOption opt =
				(CollectionFilterOptions.RarityFilterOption) rarityCombo.getSelectedItem();
			RarityMath.Tier next = opt == null ? null : opt.getTier();
			if (next == collectionRarityFilter)
			{
				return;
			}
			collectionRarityFilter = next;
			refreshCollectionTabUi();
		});
		filters.add(labeledCollectionFilter("Rarity", rarityCombo));

		DefaultComboBoxModel<CollectionListModel.SortMode> sortModel =
			new DefaultComboBoxModel<>(CollectionListModel.SortMode.values());
		JComboBox<CollectionListModel.SortMode> sortCombo = styleCollectionCombo(new JComboBox<>(sortModel));
		sortCombo.setSelectedItem(collectionSortMode);
		sortCombo.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(
				javax.swing.JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof CollectionListModel.SortMode)
				{
					setText(((CollectionListModel.SortMode) value).getLabel());
				}
				return c;
			}
		});
		sortCombo.addActionListener(e ->
		{
			CollectionListModel.SortMode next = (CollectionListModel.SortMode) sortCombo.getSelectedItem();
			if (next == null || next == collectionSortMode)
			{
				return;
			}
			collectionSortMode = next;
			refreshCollectionTabUi();
		});
		filters.add(labeledCollectionFilter("Sort by", sortCombo));

		toolbar.add(filters);
		toolbar.add(buildCollectionProgressLabel(selectedPack));

		return toolbar;
	}

	private JLabel buildCollectionProgressLabel(BoosterPackDefinition selectedPack)
	{
		PackCloseSnapshot snap = snapshotSupplier.get();
		List<CardDefinition> allCards = cardDatabase.getCards();
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(allCards);
		final String label;
		final int owned;
		final int total;
		if (selectedPack == null)
		{
			CloudSidebarCollectionStats stats = TcgPublicStatsCalculator.resolveOverview(
				snap, allCards, rollPool);
			label = "Collection";
			owned = stats.getUniqueOwned();
			total = stats.getTotalCardPool();
		}
		else
		{
			int[] progress = ShopProgress.ownedTotal(selectedPack, allCards, rollPool, snap.owned);
			label = selectedPack.getName() == null || selectedPack.getName().isBlank()
				? "Set"
				: selectedPack.getName();
			owned = progress[0];
			total = progress[2];
		}
		double pct = total <= 0 ? 0d : (100d * owned) / total;
		JLabel progressLabel = new JLabel(String.format("%s: %s / %s (%.2f%%)",
			label, SidebarLayout.format(owned), SidebarLayout.format(total), pct));
		progressLabel.setForeground(new Color(0xCCCCCC));
		progressLabel.setFont(FontManager.getRunescapeSmallFont());
		progressLabel.setHorizontalAlignment(SwingConstants.CENTER);
		progressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		progressLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
		progressLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, progressLabel.getPreferredSize().height));
		return progressLabel;
	}

	private void refreshCollectionTabUi()
	{
		render();
		onRendered.run();
	}

	private static BoosterPackDefinition findPackById(List<BoosterPackDefinition> packs, String packId)
	{
		if (packId == null || packId.isBlank() || packs == null)
		{
			return null;
		}
		for (BoosterPackDefinition pack : packs)
		{
			if (pack == null)
			{
				continue;
			}
			if (packId.equals(pack.getCollectionKey()) || packId.equals(pack.getId()))
			{
				return pack;
			}
		}
		return null;
	}

	private List<BoosterPackDefinition> collectionFilterPacks()
	{
		List<BoosterPackDefinition> out = new ArrayList<>();
		Set<String> seenKeys = new HashSet<>();
		for (BoosterPackDefinition pack : packCatalogService.getVisibleBoosters())
		{
			if (pack == null || pack.getCategoryFilters().isEmpty())
			{
				continue;
			}
			String key = pack.getCollectionKey();
			if (key == null || key.isBlank() || !seenKeys.add(key))
			{
				continue;
			}
			out.add(pack);
		}
		return out;
	}

	private JPanel labeledCollectionFilter(String labelText, JComponent field)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		JLabel label = new JLabel(labelText);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());
		row.add(label, BorderLayout.WEST);
		row.add(field, BorderLayout.CENTER);
		return row;
	}

	private JTextField createCollectionSearchField()
	{
		JTextField field = new JTextField();
		field.setFont(FontManager.getRunescapeSmallFont());
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		field.setBorder(BorderFactory.createCompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(2, 4, 2, 4)));
		javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				onCollectionSearchEdited();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				onCollectionSearchEdited();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				onCollectionSearchEdited();
			}
		};
		field.getDocument().addDocumentListener(listener);
		return field;
	}

	private void onCollectionSearchEdited()
	{
		String next = collectionSearchField.getText() == null ? "" : collectionSearchField.getText();
		if (next.equals(collectionSearchQuery))
		{
			return;
		}
		collectionSearchQuery = next;
		if (!Boolean.TRUE.equals(isActive.get()))
		{
			return;
		}
		scheduleCollectionListRebuildFromCurrentFilters();
	}

	private static <T> JComboBox<T> styleCollectionCombo(JComboBox<T> combo)
	{
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		combo.setForeground(Color.WHITE);
		combo.setFocusable(false);
		combo.setMaximumRowCount(12);
		return combo;
	}
}

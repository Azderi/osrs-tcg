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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Collection tab controller: owns the filter/sort/search toolbar and the card list, and rebuilds the row
 * list on a background executor so filtering/sorting large collections doesn't block the EDT. All Swing
 * mutation is dispatched back onto the EDT via {@link SwingUtilities#invokeLater}.
 */
@Slf4j
public final class CollectionTab
{
	/** {@link CardLayout} key for the card-list panel when it has rows to show. */
	public static final String LIST_CARD = "list";
	/** {@link CardLayout} key for the placeholder panel shown when the filtered list is empty. */
	public static final String EMPTY_CARD = "empty";
	/** {@link CardLayout} key for the full card-face preview shown when a card name is clicked. */
	public static final String PREVIEW_CARD = "preview";
	private static final int ROW_HEIGHT = 24;

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
	private final CardPreviewPanel collectionPreviewPanel;

	private String collectionPackFilterId;
	private RarityMath.Tier collectionRarityFilter;
	private CollectionListModel.SortMode collectionSortMode = CollectionListModel.SortMode.SCORE_DESC;
	private String collectionSearchQuery = "";
	/** Whichever of {@link #LIST_CARD}/{@link #EMPTY_CARD} was last shown, so the preview's back button returns to the right one. */
	private String lastListCard = LIST_CARD;
	/** True while the full card preview is showing, so a background rebuild ({@link #applyCollectionRows}) doesn't silently kick the user out of it. */
	private boolean previewOpen;

	/** Wires the collaborators and the pre-built Swing components this controller drives. */
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
		JLabel collectionEmptyLabel,
		CardPreviewPanel collectionPreviewPanel)
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
		this.collectionPreviewPanel = collectionPreviewPanel;
		this.collectionPreviewPanel.setOnBack(this::closePreview);
		this.collectionSearchField = createCollectionSearchField();
	}

	/**
	 * One-time setup of the card list's appearance and behavior: styling, fixed row height, the shared row
	 * renderer, a no-op selection model (the list is display-only), and width sync on scroll pane resize.
	 * Must be called on the EDT.
	 */
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
			/** No-op: the collection list is not selectable. */
			@Override
			public void setSelectionInterval(int index0, int index1)
			{
			}

			/** No-op: the collection list is not selectable. */
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
			/** Keeps the list's fixed cell width matched to the scroll pane's current viewport width. */
			@Override
			public void componentResized(ComponentEvent e)
			{
				syncCellWidth();
			}
		});

		MouseAdapter rowInteraction = new MouseAdapter()
		{
			/** Opens the full card preview for the row under the click, if any. */
			@Override
			public void mouseClicked(MouseEvent e)
			{
				openPreviewAt(e.getPoint());
			}

			/** Shows a hand cursor while hovering a row, to signal it's clickable. */
			@Override
			public void mouseMoved(MouseEvent e)
			{
				boolean overRow = rowBoundsAt(e.getPoint()) != null;
				collectionList.setCursor(Cursor.getPredefinedCursor(
					overRow ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
			}
		};
		collectionList.addMouseListener(rowInteraction);
		collectionList.addMouseMotionListener(rowInteraction);
	}

	/** The clicked row's cell bounds if {@code point} lands inside an actual row, else {@code null}. */
	private Rectangle rowBoundsAt(Point point)
	{
		int index = collectionList.locationToIndex(point);
		if (index < 0)
		{
			return null;
		}
		Rectangle bounds = collectionList.getCellBounds(index, index);
		return bounds != null && bounds.contains(point) ? bounds : null;
	}

	/** Resolves the row under {@code point} to its {@link CardDefinition} and swaps the panel to the full preview. */
	private void openPreviewAt(Point point)
	{
		if (rowBoundsAt(point) == null)
		{
			return;
		}
		int index = collectionList.locationToIndex(point);
		CollectionListModel.Row row = collectionList.getModel().getElementAt(index);
		if (row == null || row.getName().isBlank())
		{
			return;
		}
		CardDefinition def = cardDatabase.findByName(row.getName()).orElse(null);
		if (def == null)
		{
			return;
		}
		collectionPreviewPanel.show(def, row.isFoil(), row.getTier());
		previewOpen = true;
		((CardLayout) collectionListHost.getLayout()).show(collectionListHost, PREVIEW_CARD);
	}

	/** Swaps back from the preview to whichever of {@link #LIST_CARD}/{@link #EMPTY_CARD} was last showing. */
	public void closePreview()
	{
		previewOpen = false;
		((CardLayout) collectionListHost.getLayout()).show(collectionListHost, lastListCard);
	}

	/** Bumps the build generation so any in-flight background row rebuild discards its result once done. */
	public void cancelPendingRebuilds()
	{
		buildGen.incrementAndGet();
	}

	/** Clears the visible list data without touching filters or triggering a rebuild. */
	public void clearList()
	{
		collectionList.setListData(new CollectionListModel.Row[0]);
	}

	/** Applies {@link #contentWidth} to the list's fixed cell width if it has changed. */
	private void syncCellWidth()
	{
		int w = contentWidth.getAsInt();
		if (collectionList.getFixedCellWidth() != w)
		{
			collectionList.setFixedCellWidth(w);
		}
	}

	/**
	 * Rebuilds the toolbar and swaps it plus the list host into {@link #collectionContent}, then kicks off
	 * an async rebuild of the row list for the current filters. Must be called on the EDT.
	 */
	public void render()
	{
		PackCloseSnapshot snap = snapshotSupplier.get();
		List<CardDefinition> allCards = cardDatabase.getCards();
		List<BoosterPackDefinition> packs = collectionFilterPacks();
		BoosterPackDefinition selectedPack = resolveSelectedPack(packs);
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(allCards);

		collectionContent.removeAll();
		JPanel toolbar = buildCollectionToolbar(packs, selectedPack, snap, allCards, rollPool);
		collectionContent.add(toolbar, BorderLayout.NORTH);
		collectionContent.add(collectionListHost, BorderLayout.CENTER);
		collectionContent.revalidate();
		collectionContent.repaint();

		scheduleCollectionListRebuild(snap, allCards, selectedPack);
	}

	/** Looks up the current pack filter in {@code packs}; clears the filter if it no longer matches any pack. */
	private BoosterPackDefinition resolveSelectedPack(List<BoosterPackDefinition> packs)
	{
		BoosterPackDefinition selected = PackCatalogService.findById(packs, collectionPackFilterId);
		if (collectionPackFilterId != null && selected == null)
		{
			collectionPackFilterId = null;
			return null;
		}
		return selected;
	}

	/**
	 * Snapshots the current filter/sort state and schedules the row build on {@link #scheduler}, applying
	 * the result back on the EDT only if no newer rebuild has been started ({@link #buildGen} check) and
	 * falling back to an empty list on failure.
	 */
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

	/** Pushes a completed row build into the list and flips the {@link CardLayout} between empty/list cards. Must run on the EDT. */
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
			lastListCard = EMPTY_CARD;
		}
		else
		{
			collectionList.setListData(rows.toArray(new CollectionListModel.Row[0]));
			lastListCard = LIST_CARD;
		}
		if (!previewOpen)
		{
			cards.show(collectionListHost, lastListCard);
		}
		syncCellWidth();
		collectionListHost.revalidate();
		collectionListHost.repaint();
	}

	/** Builds the search field, pack/rarity/sort combo rows, and the progress label above the list. */
	private JPanel buildCollectionToolbar(
		List<BoosterPackDefinition> packs,
		BoosterPackDefinition selectedPack,
		PackCloseSnapshot snap,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
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

		CollectionFilterOptions.PackComboModel packComboModel =
			CollectionFilterOptions.packComboModel(packs, selectedPack);
		JComboBox<CollectionFilterOptions.PackFilterOption> packCombo =
			styleCollectionCombo(new JComboBox<>(packComboModel.model));
		packCombo.setSelectedItem(packComboModel.selected);
		wireFilterCombo(packCombo,
			opt ->
			{
				String nextId = opt == null ? null : opt.getPackId();
				return (nextId == null && collectionPackFilterId == null)
					|| (nextId != null && nextId.equals(collectionPackFilterId));
			},
			opt -> collectionPackFilterId = opt == null ? null : opt.getPackId());
		filters.add(labeledCollectionFilter("Collection", packCombo));

		CollectionFilterOptions.RarityComboModel rarityComboModel =
			CollectionFilterOptions.rarityComboModel(collectionRarityFilter);
		JComboBox<CollectionFilterOptions.RarityFilterOption> rarityCombo =
			styleCollectionCombo(new JComboBox<>(rarityComboModel.model));
		rarityCombo.setSelectedItem(rarityComboModel.selected);
		wireFilterCombo(rarityCombo,
			opt ->
			{
				RarityMath.Tier next = opt == null ? null : opt.getTier();
				return next == collectionRarityFilter;
			},
			opt -> collectionRarityFilter = opt == null ? null : opt.getTier());
		filters.add(labeledCollectionFilter("Rarity", rarityCombo));

		DefaultComboBoxModel<CollectionListModel.SortMode> sortModel =
			new DefaultComboBoxModel<>(CollectionListModel.SortMode.values());
		JComboBox<CollectionListModel.SortMode> sortCombo = styleCollectionCombo(new JComboBox<>(sortModel));
		sortCombo.setSelectedItem(collectionSortMode);
		wireFilterCombo(sortCombo, next -> next == null || next == collectionSortMode, next -> collectionSortMode = next);
		filters.add(labeledCollectionFilter("Sort by", sortCombo));

		toolbar.add(filters);
		toolbar.add(buildCollectionProgressLabel(selectedPack, snap, allCards, rollPool));

		return toolbar;
	}

	/**
	 * Builds the "X: owned / total (pct%)" label: overall collection stats when no pack filter is active,
	 * or that pack's set-completion progress otherwise.
	 */
	private JLabel buildCollectionProgressLabel(
		BoosterPackDefinition selectedPack,
		PackCloseSnapshot snap,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
	{
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

	/** Re-renders the tab after a filter change, then notifies the host panel via {@link #onRendered}. */
	private void refreshCollectionTabUi()
	{
		render();
		onRendered.run();
	}

	/** Visible boosters that have category filters and a distinct collection key, deduplicated by that key. */
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

	/** Wraps a filter control with a left-aligned text label. */
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

	/** Builds the styled search text field, wiring its document listener to {@link #onCollectionSearchEdited}. */
	private JTextField createCollectionSearchField()
	{
		JTextField field = new JTextField();
		field.setFont(FontManager.getRunescapeSmallFont());
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		SidebarLayout.styleOutlinedButton(field, ColorScheme.MEDIUM_GRAY_COLOR, 2, 4, 2, 4);
		field.getDocument().addDocumentListener(documentListener(this::onCollectionSearchEdited));
		return field;
	}

	/** Stores the new search text and, while the tab is active, schedules a row rebuild for it (without a full re-render). */
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
		List<BoosterPackDefinition> packs = collectionFilterPacks();
		scheduleCollectionListRebuild(
			snapshotSupplier.get(),
			cardDatabase.getCards(),
			resolveSelectedPack(packs));
	}

	/** Adapts a {@link DocumentListener}'s three callbacks onto a single {@code onChange} callback. */
	private static DocumentListener documentListener(Runnable onChange)
	{
		return new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onChange.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onChange.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onChange.run();
			}
		};
	}

	/**
	 * Wires a combo box so selecting a genuinely different item (per {@code unchanged}) applies it via
	 * {@code apply} and triggers a full tab re-render.
	 */
	private <T> void wireFilterCombo(JComboBox<T> combo, Predicate<T> unchanged, Consumer<T> apply)
	{
		combo.addActionListener(e ->
		{
			@SuppressWarnings("unchecked")
			T next = (T) combo.getSelectedItem();
			if (unchanged.test(next))
			{
				return;
			}
			apply.accept(next);
			refreshCollectionTabUi();
		});
	}

	/** Applies the common font/color/focus styling shared by the collection tab's filter combo boxes. */
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

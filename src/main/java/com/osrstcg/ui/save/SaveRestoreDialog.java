package com.osrstcg.ui.save;

import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.state.TcgState;
import com.osrstcg.persist.TcgSaveMetadataEntry;
import com.osrstcg.persist.TcgStateFileBackupStore;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.NumberFormatting;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Modal picker for choosing a current-profile disk save to upload during cloud migrate.
 */
public final class SaveRestoreDialog extends JDialog
{
	private static final DateTimeFormatter DISPLAY_TIME =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter LIST_TIME =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final TcgStateService stateService;
	private final Consumer<String> onUploadAccepted;
	private final DefaultListModel<TcgSaveMetadataEntry> listModel = new DefaultListModel<>();
	private final JList<TcgSaveMetadataEntry> saveList = new JList<>(listModel);
	private final JLabel fileValue = statValue();
	private final JLabel savedAtValue = statValue();
	private final JLabel triggerValue = statValue();
	private final JLabel creditsValue = statValue();
	private final JLabel cardsValue = statValue();
	private final JLabel foilsValue = statValue();
	private final JLabel packsValue = statValue();
	private final JLabel distinctValue = statValue();
	private final JButton uploadButton = new JButton("Upload");

	public SaveRestoreDialog(TcgStateService stateService, Consumer<String> onUploadAccepted)
	{
		super((java.awt.Frame) null, "OSRS TCG - Upload save", true);
		this.stateService = stateService;
		this.onUploadAccepted = onUploadAccepted;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		setMinimumSize(new Dimension(620, 460));
		setPreferredSize(new Dimension(680, 500));

		JPanel root = new JPanel(new BorderLayout(10, 10));
		root.setBorder(new EmptyBorder(12, 12, 12, 12));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel(new BorderLayout(8, 8));
		north.setOpaque(false);
		JLabel title = new JLabel("Select a save to upload");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		north.add(title, BorderLayout.NORTH);
		JLabel subtitle = new JLabel("This collection will be sent to the OSRS TCG server.");
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		north.add(subtitle, BorderLayout.SOUTH);
		root.add(north, BorderLayout.NORTH);

		saveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		saveList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		saveList.setForeground(Color.WHITE);
		saveList.setFont(FontManager.getRunescapeSmallFont());
		saveList.setCellRenderer(new SaveListCellRenderer());
		saveList.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				updateStats(saveList.getSelectedValue());
			}
		});

		JScrollPane listScroll = new JScrollPane(saveList);
		listScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		listScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		listScroll.setPreferredSize(new Dimension(360, 300));

		JPanel statsPanel = buildStatsPanel();
		JPanel center = new JPanel(new BorderLayout(10, 0));
		center.setOpaque(false);
		center.add(listScroll, BorderLayout.WEST);
		center.add(statsPanel, BorderLayout.CENTER);
		root.add(center, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setOpaque(false);
		JButton cancel = darkButton("Cancel");
		cancel.addActionListener(e -> dispose());
		stylePrimaryButton(uploadButton);
		uploadButton.setEnabled(false);
		uploadButton.addActionListener(e -> acceptUpload());
		buttons.add(cancel);
		buttons.add(uploadButton);
		root.add(buttons, BorderLayout.SOUTH);

		setContentPane(root);
		reloadSaves();
		pack();
		setLocationRelativeTo(null);
	}

	private void reloadSaves()
	{
		listModel.clear();
		List<TcgSaveMetadataEntry> saves = stateService.listDiskSaves();
		for (TcgSaveMetadataEntry entry : saves)
		{
			listModel.addElement(entry);
		}
		if (!listModel.isEmpty())
		{
			saveList.setSelectedIndex(0);
		}
		else
		{
			updateStats(null);
		}
	}

	private void acceptUpload()
	{
		TcgSaveMetadataEntry selected = saveList.getSelectedValue();
		if (selected == null || selected.getName() == null)
		{
			return;
		}
		String file = selected.getName();
		dispose();
		if (onUploadAccepted != null)
		{
			onUploadAccepted.accept(file);
		}
	}

	private void updateStats(TcgSaveMetadataEntry entry)
	{
		if (entry == null)
		{
			fileValue.setText("-");
			savedAtValue.setText("-");
			triggerValue.setText("-");
			creditsValue.setText("-");
			cardsValue.setText("-");
			foilsValue.setText("-");
			packsValue.setText("-");
			distinctValue.setText("-");
			uploadButton.setEnabled(false);
			return;
		}

		fileValue.setText(displayName(entry.getName()));
		savedAtValue.setText(formatSavedAt(entry.getSavedAt()));
		triggerValue.setText(entry.getTrigger() == null || entry.getTrigger().isEmpty()
			? "UNKNOWN"
			: entry.getTrigger());

		Optional<TcgState> peeked = stateService.peekDiskSave(entry.getName());
		if (peeked.isEmpty())
		{
			creditsValue.setText(NumberFormatting.format(entry.getCredits()));
			cardsValue.setText(NumberFormatting.format(entry.getCardCount()));
			foilsValue.setText("-");
			packsValue.setText("-");
			distinctValue.setText("-");
			uploadButton.setEnabled(false);
			return;
		}

		TcgState state = peeked.get();
		List<OwnedCardInstance> instances = state.getCollectionState().getOwnedInstances();
		long foils = instances.stream().filter(OwnedCardInstance::isFoil).count();
		creditsValue.setText(NumberFormatting.format(state.getEconomyState().getCredits()));
		cardsValue.setText(NumberFormatting.format(instances.size()));
		foilsValue.setText(NumberFormatting.format(foils));
		packsValue.setText(NumberFormatting.format(state.getEconomyState().getOpenedPacks()));
		distinctValue.setText(NumberFormatting.format(state.getCollectionState().getOwnedCards().size()));
		uploadButton.setEnabled(true);
	}

	private JPanel buildStatsPanel()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 12, 10, 12)));

		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(3, 0, 3, 10);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 0;

		addStatRow(panel, c, "Name", fileValue);
		addStatRow(panel, c, "Saved at", savedAtValue);
		addStatRow(panel, c, "Trigger", triggerValue);
		addStatRow(panel, c, "Credits", creditsValue);
		addStatRow(panel, c, "Cards", cardsValue);
		addStatRow(panel, c, "Foils", foilsValue);
		addStatRow(panel, c, "Packs opened", packsValue);
		addStatRow(panel, c, "Unique cards", distinctValue);

		c.gridy++;
		c.weighty = 1;
		c.gridwidth = 2;
		panel.add(new JLabel(), c);
		return panel;
	}

	private static void addStatRow(JPanel panel, GridBagConstraints c, String label, JLabel value)
	{
		c.gridx = 0;
		c.weightx = 0;
		JLabel key = new JLabel(label);
		key.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		key.setFont(FontManager.getRunescapeSmallFont());
		panel.add(key, c);

		c.gridx = 1;
		c.weightx = 1;
		panel.add(value, c);
		c.gridy++;
	}

	private static JLabel statValue()
	{
		JLabel label = new JLabel("-");
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	private static JButton darkButton(String text)
	{
		JButton button = new JButton(text);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setForeground(Color.WHITE);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(4, 12, 4, 12)));
		return button;
	}

	private static void stylePrimaryButton(JButton button)
	{
		button.setBackground(ColorScheme.BRAND_ORANGE.darker());
		button.setForeground(Color.WHITE);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.BRAND_ORANGE),
			new EmptyBorder(4, 12, 4, 12)));
	}

	static String displayName(String name)
	{
		if (name == null)
		{
			return "-";
		}
		if (TcgStateFileBackupStore.MASTER_FILENAME.equalsIgnoreCase(name))
		{
			return "tcg.save (master)";
		}
		if (name.length() <= 16)
		{
			return name;
		}
		return name.substring(0, 12) + "…" + name.substring(name.length() - 4);
	}

	static String formatListLabel(TcgSaveMetadataEntry entry)
	{
		String when = formatListTime(entry.getSavedAt());
		String hashPrefix = hashPrefix(entry);
		return String.format(Locale.US, "%s - %s credits - %s cards (%s)",
			when,
			NumberFormatting.format(entry.getCredits()),
			NumberFormatting.format(entry.getCardCount()),
			hashPrefix);
	}

	static String hashPrefix(TcgSaveMetadataEntry entry)
	{
		String hash = entry.getHash();
		if (hash == null || hash.isEmpty())
		{
			String name = entry.getName();
			if (name != null && name.length() >= 5 && !TcgStateFileBackupStore.MASTER_FILENAME.equalsIgnoreCase(name))
			{
				hash = name;
			}
		}
		if (hash == null || hash.length() < 5)
		{
			return "?????";
		}
		return hash.substring(0, 5).toLowerCase(Locale.ROOT);
	}

	private static String formatSavedAt(String savedAt)
	{
		if (savedAt == null || savedAt.isEmpty())
		{
			return "-";
		}
		try
		{
			return DISPLAY_TIME.format(Instant.parse(savedAt));
		}
		catch (Exception ex)
		{
			return savedAt;
		}
	}

	private static String formatListTime(String savedAt)
	{
		if (savedAt == null || savedAt.isEmpty())
		{
			return "-";
		}
		try
		{
			return LIST_TIME.format(Instant.parse(savedAt));
		}
		catch (Exception ex)
		{
			return savedAt;
		}
	}

	private static final class SaveListCellRenderer extends DefaultListCellRenderer
	{
		@Override
		public java.awt.Component getListCellRendererComponent(
			JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			setOpaque(true);
			setFont(FontManager.getRunescapeSmallFont());
			if (value instanceof TcgSaveMetadataEntry)
			{
				TcgSaveMetadataEntry entry = (TcgSaveMetadataEntry) value;
				setText(formatListLabel(entry));
				setToolTipText(formatSavedAt(entry.getSavedAt()) + " - "
					+ (entry.getName() == null ? "" : entry.getName()));
			}
			if (isSelected)
			{
				setBackground(new Color(0x00, 0x5A, 0x70));
				setForeground(Color.WHITE);
			}
			else
			{
				setBackground(ColorScheme.DARKER_GRAY_COLOR);
				setForeground(Color.WHITE);
			}
			setBorder(new EmptyBorder(4, 8, 4, 8));
			setHorizontalAlignment(SwingConstants.LEFT);
			return this;
		}
	}
}

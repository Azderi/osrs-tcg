package com.osrstcg.ui.save;

import com.osrstcg.persist.TcgSaveMetadataEntry;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.NumberFormatting;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Thin modal picker: choose a legacy disk save to upload during cloud migrate.
 */
public final class MigrateSavePickerDialog extends JDialog
{
	private static final DateTimeFormatter LIST_TIME =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final Consumer<TcgSaveMetadataEntry> onUploadAccepted;
	private final DefaultListModel<TcgSaveMetadataEntry> listModel = new DefaultListModel<>();
	private final JList<TcgSaveMetadataEntry> saveList = new JList<>(listModel);
	private final JButton uploadButton = new JButton("Upload");

	public MigrateSavePickerDialog(TcgStateService stateService, Consumer<TcgSaveMetadataEntry> onUploadAccepted)
	{
		super((java.awt.Frame) null, "OSRS TCG - Upload save", true);
		this.onUploadAccepted = onUploadAccepted;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		setMinimumSize(new Dimension(420, 320));
		setPreferredSize(new Dimension(480, 360));

		JPanel root = new JPanel(new BorderLayout(10, 10));
		root.setBorder(new EmptyBorder(12, 12, 12, 12));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Select a save to upload to the OSRS TCG server");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		root.add(title, BorderLayout.NORTH);

		saveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		saveList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		saveList.setForeground(Color.WHITE);
		saveList.setFont(FontManager.getRunescapeSmallFont());
		saveList.setCellRenderer(new SaveListCellRenderer());
		saveList.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				uploadButton.setEnabled(saveList.getSelectedValue() != null);
			}
		});
		JScrollPane scroll = new JScrollPane(saveList);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		root.add(scroll, BorderLayout.CENTER);

		JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		south.setOpaque(false);
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());
		uploadButton.setEnabled(false);
		uploadButton.addActionListener(e -> acceptSelection());
		south.add(cancel);
		south.add(uploadButton);
		root.add(south, BorderLayout.SOUTH);

		setContentPane(root);
		pack();
		setLocationRelativeTo(null);

		List<TcgSaveMetadataEntry> entries = stateService.listDiskSaves();
		for (TcgSaveMetadataEntry entry : entries)
		{
			if (entry != null && entry.getName() != null && !entry.getName().isEmpty())
			{
				listModel.addElement(entry);
			}
		}
		if (!listModel.isEmpty())
		{
			saveList.setSelectedIndex(0);
		}
	}

	private void acceptSelection()
	{
		TcgSaveMetadataEntry selected = saveList.getSelectedValue();
		if (selected == null)
		{
			return;
		}
		dispose();
		if (onUploadAccepted != null)
		{
			onUploadAccepted.accept(selected);
		}
	}

	private static String formatListLabel(TcgSaveMetadataEntry entry)
	{
		String name = displaySaveName(entry.getName());
		String when = formatSavedAt(entry.getSavedAt());
		return name + "  ·  " + NumberFormatting.format(entry.getCardCount()) + " cards  ·  "
			+ NumberFormatting.format(entry.getCredits()) + " credits  ·  " + when;
	}

	/** Hash-based save filenames are 64-char hex; list UI shows only a short prefix. */
	private static String displaySaveName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return "?";
		}
		if (name.length() >= 5 && name.chars().allMatch(c ->
			(c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
		{
			return name.substring(0, 5).toLowerCase(Locale.ROOT);
		}
		return name;
	}

	private static String formatSavedAt(String savedAt)
	{
		if (savedAt == null || savedAt.isEmpty())
		{
			return "-";
		}
		try
		{
			return LIST_TIME.format(Instant.parse(savedAt));
		}
		catch (Exception ignored)
		{
			return savedAt;
		}
	}

	private static final class SaveListCellRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(
			JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value instanceof TcgSaveMetadataEntry)
			{
				setText(formatListLabel((TcgSaveMetadataEntry) value));
			}
			setBorder(new EmptyBorder(4, 8, 4, 8));
			return this;
		}
	}
}

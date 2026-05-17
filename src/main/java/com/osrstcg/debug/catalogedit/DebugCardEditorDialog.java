package com.osrstcg.debug.catalogedit;

import com.google.gson.JsonObject;
import com.osrstcg.data.CardDefinition;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

final class DebugCardEditorDialog extends JDialog
{
	private final DebugCardJsonFileStore fileStore;
	private final DebugCatalogReloader reloader;
	private final Path cardJsonPath;
	private final String cardName;

	private final JTextField categoryField = new JTextField(40);
	private final JTextField imageUrlField = new JTextField(40);
	private final JTextField levelField = new JTextField(8);
	private final JTextField valueField = new JTextField(12);
	private final JTextField overrideScoreField = new JTextField(12);
	private final JCheckBox clearOverrideScore = new JCheckBox("Clear override score");
	private final JTextArea examineArea = new JTextArea(4, 40);
	private final JCheckBox questItemCheck = new JCheckBox("Quest item");

	DebugCardEditorDialog(
		Component parent,
		DebugCardJsonFileStore fileStore,
		DebugCatalogReloader reloader,
		Path cardJsonPath,
		CardDefinition card,
		JsonObject json)
	{
		super(JOptionPane.getFrameForComponent(parent), "DEBUG — Edit card definition", true);
		this.fileStore = fileStore;
		this.reloader = reloader;
		this.cardJsonPath = cardJsonPath;
		this.cardName = card.getName();

		setLayout(new BorderLayout(8, 8));
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel header = new JLabel("<html><b>" + escapeHtml(cardName) + "</b> — writes Card.json on save</html>");
		header.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
		header.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(header, BorderLayout.NORTH);

		JPanel form = new JPanel(new GridBagLayout());
		form.setOpaque(false);
		form.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(2, 0, 2, 8);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.weightx = 1.0;

		int row = 0;
		addRow(form, gbc, row++, "File", new JLabel(cardJsonPath.toString()));
		addRow(form, gbc, row++, "Category (comma-separated)", categoryField);
		addRow(form, gbc, row++, "Image URL", imageUrlField);
		addRow(form, gbc, row++, "Level (empty = omit)", levelField);
		addRow(form, gbc, row++, "Value (empty = omit)", valueField);

		JPanel overrideRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		overrideRow.setOpaque(false);
		overrideRow.add(overrideScoreField);
		overrideRow.add(clearOverrideScore);
		addRow(form, gbc, row++, "Override score", overrideRow);

		examineArea.setLineWrap(true);
		examineArea.setWrapStyleWord(true);
		examineArea.setFont(FontManager.getRunescapeFont());
		addRow(form, gbc, row++, "Examine", new JScrollPane(examineArea));

		JPanel questRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		questRow.setOpaque(false);
		questRow.add(questItemCheck);
		addRow(form, gbc, row, "Flags", questRow);

		populateFrom(json, card);
		styleFields();
		add(form, BorderLayout.CENTER);

		JLabel footer = new JLabel(
			"<html><i>Deleting removes this card from the catalog. Owned copies may remain in your collection.</i></html>");
		footer.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 4, 12));

		JPanel south = new JPanel(new BorderLayout());
		south.setOpaque(false);
		south.add(footer, BorderLayout.NORTH);
		south.add(buildButtonBar(), BorderLayout.SOUTH);
		add(south, BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(parent);
	}

	private void populateFrom(JsonObject json, CardDefinition card)
	{
		List<String> cats = json != null
			? DebugCardJsonFileStore.categoryField(json)
			: card.getCategoryTags();
		categoryField.setText(DebugCardJsonFileStore.formatCategories(cats));

		String imageUrl = json != null ? DebugCardJsonFileStore.stringField(json, "imageUrl") : card.getImageUrl();
		imageUrlField.setText(imageUrl == null ? "" : imageUrl);

		Integer level = json != null ? DebugCardJsonFileStore.intField(json, "level") : card.getLevel();
		levelField.setText(level == null ? "" : String.valueOf(level));

		Long value = json != null ? DebugCardJsonFileStore.longField(json, "value") : card.getValue();
		valueField.setText(value == null ? "" : String.valueOf(value));

		Long override = json != null ? DebugCardJsonFileStore.longField(json, "overrideScore") : card.getOverrideScore();
		overrideScoreField.setText(override == null ? "" : String.valueOf(override));
		clearOverrideScore.setSelected(override == null);

		String examine = json != null ? DebugCardJsonFileStore.stringField(json, "examine") : card.getExamine();
		examineArea.setText(examine == null ? "" : examine);

		Boolean quest = json != null ? DebugCardJsonFileStore.boolField(json, "questItem") : card.getQuestItem();
		questItemCheck.setSelected(Boolean.TRUE.equals(quest));
	}

	private JPanel buildButtonBar()
	{
		JButton saveBtn = new JButton("Save");
		JButton deleteBtn = new JButton("Delete from catalog");
		JButton cancelBtn = new JButton("Cancel");
		saveBtn.addActionListener(e -> onSave());
		deleteBtn.addActionListener(e -> onDelete());
		cancelBtn.addActionListener(e -> dispose());

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
		bar.setOpaque(false);
		bar.add(deleteBtn);
		bar.add(cancelBtn);
		bar.add(saveBtn);
		return bar;
	}

	private void onSave()
	{
		try
		{
			DebugCardJsonFileStore.CardJsonEdit edit = buildEditFromForm();
			fileStore.updateCard(cardJsonPath, cardName, edit);
			reloader.reloadEntireCatalog();
			dispose();
		}
		catch (Exception ex)
		{
			showError("Save failed", ex);
		}
	}

	private void onDelete()
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			"Remove \"" + cardName + "\" from Card.json?\nOwned collection copies are not removed.",
			"Delete card from catalog",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		try
		{
			fileStore.deleteCard(cardJsonPath, cardName);
			reloader.reloadEntireCatalog();
			dispose();
		}
		catch (Exception ex)
		{
			showError("Delete failed", ex);
		}
	}

	private DebugCardJsonFileStore.CardJsonEdit buildEditFromForm() throws IllegalArgumentException
	{
		String imageUrl = imageUrlField.getText().trim();
		String examine = examineArea.getText();
		Long value = parseOptionalLong(valueField.getText(), "Value");
		Integer level = parseOptionalInt(levelField.getText(), "Level");
		Long overrideScore = clearOverrideScore.isSelected()
			? null
			: parseOptionalLong(overrideScoreField.getText(), "Override score");
		return new DebugCardJsonFileStore.CardJsonEdit(
			DebugCardJsonFileStore.parseCategories(categoryField.getText()),
			imageUrl.isEmpty() ? null : imageUrl,
			examine == null || examine.isEmpty() ? null : examine,
			value,
			level,
			overrideScore,
			questItemCheck.isSelected());
	}

	private static Long parseOptionalLong(String raw, String label)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Long.parseLong(raw.trim());
		}
		catch (NumberFormatException ex)
		{
			throw new IllegalArgumentException(label + " must be a whole number or empty.");
		}
	}

	private static Integer parseOptionalInt(String raw, String label)
	{
		Long v = parseOptionalLong(raw, label);
		if (v == null)
		{
			return null;
		}
		if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException(label + " is out of range.");
		}
		return v.intValue();
	}

	private void showError(String title, Exception ex)
	{
		JOptionPane.showMessageDialog(this,
			DebugCardJsonFileStore.readErrorMessage(ex),
			title,
			JOptionPane.ERROR_MESSAGE);
	}

	private static void addRow(JPanel form, GridBagConstraints gbc, int row, String label, Component field)
	{
		gbc.gridx = 0;
		gbc.gridy = row;
		gbc.weightx = 0;
		JLabel lbl = new JLabel(label + ":");
		lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		form.add(lbl, gbc);
		gbc.gridx = 1;
		gbc.weightx = 1.0;
		form.add(field, gbc);
	}

	private void styleFields()
	{
		for (JTextField f : new JTextField[] {categoryField, imageUrlField, levelField, valueField, overrideScoreField})
		{
			f.setFont(FontManager.getRunescapeFont());
			f.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			f.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		examineArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		examineArea.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		questItemCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		clearOverrideScore.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
	}

	private static String escapeHtml(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}

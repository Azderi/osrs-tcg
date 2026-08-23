package com.osrstcg.ui.collection;

import com.osrstcg.util.NumberFormatting;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.function.IntSupplier;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public final class CollectionRowRenderer extends JPanel
	implements ListCellRenderer<CollectionListModel.Row>
{
	private final JLabel name = new JLabel();
	private final JLabel score = new JLabel();
	private final IntSupplier contentWidth;

	public CollectionRowRenderer(IntSupplier contentWidth)
	{
		this.contentWidth = contentWidth;
		setLayout(new BorderLayout(6, 0));
		// Right inset from commit 3ae86d9 - keeps scores clear of the collection scrollbar.
		setBorder(new EmptyBorder(3, 6, 3, 12));
		setOpaque(true);
		name.setFont(FontManager.getRunescapeSmallFont());
		score.setFont(FontManager.getRunescapeSmallFont());
		score.setForeground(new Color(0xAAAAAA));
		score.setHorizontalAlignment(SwingConstants.RIGHT);
		add(name, BorderLayout.CENTER);
		add(score, BorderLayout.EAST);
	}

	@Override
	public Component getListCellRendererComponent(
		JList<? extends CollectionListModel.Row> list,
		CollectionListModel.Row value,
		int index,
		boolean isSelected,
		boolean cellHasFocus)
	{
		if (value == null)
		{
			name.setText("");
			score.setText("");
		}
		else
		{
			name.setText(value.isFoil() ? value.getName() + " ★" : value.getName());
			name.setForeground(value.getTier().getColor());
			score.setText(NumberFormatting.formatCompact(value.getScore()));
		}
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		clampRowWidth();
		return this;
	}

	private void clampRowWidth()
	{
		int w = contentWidth.getAsInt();
		setAlignmentX(LEFT_ALIGNMENT);
		Dimension preferred = getPreferredSize();
		setPreferredSize(new Dimension(w, preferred.height));
		setMaximumSize(new Dimension(w, preferred.height));
		setMinimumSize(new Dimension(0, preferred.height));
	}
}

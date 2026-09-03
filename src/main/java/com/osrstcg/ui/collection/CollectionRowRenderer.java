package com.osrstcg.ui.collection;

import com.osrstcg.util.NumberFormatting;
import com.osrstcg.ui.layout.SidebarLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.function.IntSupplier;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Renders one {@link CollectionListModel.Row} in the Collection tab's card {@link JList}: card name
 * (tier-colored, foil-starred) on the left, compact score on the right. Reused as a shared cell renderer,
 * so all mutation happens on the Swing EDT inside {@link #getListCellRendererComponent}.
 */
public final class CollectionRowRenderer extends JPanel
	implements ListCellRenderer<CollectionListModel.Row>
{
	private final JLabel name = new JLabel();
	private final JLabel score = new JLabel();
	private final IntSupplier contentWidth;

	/** Builds the fixed name/score label layout; {@code contentWidth} supplies the list's current cell width. */
	public CollectionRowRenderer(IntSupplier contentWidth)
	{
		this.contentWidth = contentWidth;
		setLayout(new BorderLayout(6, 0));
		setBorder(new EmptyBorder(3, 6, 3, 12));
		setOpaque(true);
		name.setFont(FontManager.getRunescapeSmallFont());
		score.setFont(FontManager.getRunescapeSmallFont());
		score.setForeground(new Color(0xAAAAAA));
		score.setHorizontalAlignment(SwingConstants.RIGHT);
		add(name, BorderLayout.CENTER);
		add(score, BorderLayout.EAST);
	}

	/** Updates the shared row panel's text/color for {@code value} and clamps it to the list's current width. */
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
		SidebarLayout.clampFixedWidth(this, contentWidth.getAsInt());
		return this;
	}
}

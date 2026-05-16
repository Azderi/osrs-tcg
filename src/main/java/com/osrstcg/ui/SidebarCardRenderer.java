package com.osrstcg.ui;

import com.osrstcg.util.NumberFormatting;
import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

final class SidebarCardRenderer
{
	private SidebarCardRenderer()
	{
	}

	static JPanel renderCardRow(String cardName, boolean foil, int quantity, Color rarityColor)
	{
		JPanel panel = new JPanel(new BorderLayout(8, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, safeColor(rarityColor).darker()),
			new EmptyBorder(5, 4, 5, 4)
		));

		JPanel textWrap = new JPanel();
		textWrap.setOpaque(false);
		textWrap.setLayout(new BoxLayout(textWrap, BoxLayout.Y_AXIS));

		JLabel nameLabel = new JLabel(shorten(cardName, 28));
		nameLabel.setToolTipText(cardName);
		nameLabel.setForeground(safeColor(rarityColor));
		nameLabel.setFont(FontManager.getRunescapeSmallFont());

		JLabel metaLabel = new JLabel(foil ? "Foil" : "Standard");
		metaLabel.setForeground(foil ? new Color(0xF2C94C) : ColorScheme.LIGHT_GRAY_COLOR);
		metaLabel.setFont(FontManager.getRunescapeSmallFont());

		textWrap.add(nameLabel);
		textWrap.add(Box.createVerticalStrut(2));
		textWrap.add(metaLabel);

		JLabel qtyLabel = new JLabel("x" + NumberFormatting.format(quantity));
		qtyLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		qtyLabel.setForeground(Color.WHITE);
		qtyLabel.setFont(FontManager.getRunescapeSmallFont());

		panel.add(textWrap, BorderLayout.CENTER);
		panel.add(qtyLabel, BorderLayout.EAST);
		return panel;
	}

	private static Color safeColor(Color color)
	{
		return color == null ? Color.WHITE : color;
	}

	private static String shorten(String value, int maxLen)
	{
		if (value == null || value.length() <= maxLen)
		{
			return value;
		}
		if (maxLen <= 3)
		{
			return value.substring(0, Math.max(0, maxLen));
		}
		return value.substring(0, maxLen - 3) + "...";
	}
}

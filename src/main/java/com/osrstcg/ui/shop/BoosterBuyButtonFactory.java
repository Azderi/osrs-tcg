package com.osrstcg.ui.shop;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.ui.layout.SidebarLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BoxLayout;
import javax.swing.GrayFilter;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** Shop booster tile chrome (icon, progress, buy button). */
final class BoosterBuyButtonFactory
{
	static final int BOOSTER_BUTTON_MIN_HEIGHT = 120;
	/** Tighter tile when pack thumbnails are omitted ({@link com.osrstcg.OsrsTcgConfig#compactShop()}). */
	static final int BOOSTER_MIN_HEIGHT_COMPACT = 72;

	private BoosterBuyButtonFactory()
	{
	}

	/**
	 * Builds one shop booster tile: title, optional pack icon (swapped for a grayscale version when the
	 * button is disabled), price, progress bar, owned/total counts, and a buy action. Must be called on the EDT.
	 */
	static JButton create(
		BoosterPackDefinition booster,
		int progressOwn,
		int progressFoilOwn,
		int progressTotal,
		int buttonWidth,
		ImageIcon packIconColor,
		boolean compact,
		Runnable onBuy)
	{
		int price = booster.getPrice();
		String title = booster.getName() == null ? "Booster" : booster.getName();
		double progressPct = progressTotal <= 0 ? 0.0 : (100.0 * progressOwn) / progressTotal;

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);

		JLabel titleLabel = shopBoosterTextLabel(SidebarLayout.htmlEscape(title));
		content.add(titleLabel);

		final JLabel iconLabel;
		final ImageIcon packIconGray;
		if (!compact && packIconColor != null)
		{
			packIconGray = new ImageIcon(GrayFilter.createDisabledImage(packIconColor.getImage()));
			iconLabel = new JLabel(packIconColor);
			iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			iconLabel.setBorder(new EmptyBorder(0, 0, 5, 0));
			content.add(iconLabel);
		}
		else
		{
			iconLabel = null;
			packIconGray = null;
		}

		content.add(shopBoosterTextLabel(SidebarLayout.format(price) + " credits"));

		content.add(new ShopPackProgressBar(ShopPackProgressBar.WIDTH_PX, progressOwn, progressFoilOwn, progressTotal));
		content.add(shopBoosterTextLabel(SidebarLayout.format(progressOwn) + " / " + SidebarLayout.format(progressTotal)));
		content.add(shopBoosterTextLabel("(" + String.format("%.2f", progressPct) + "%)"));

		JButton button = new JButton();
		button.setLayout(new BorderLayout());
		button.add(content, BorderLayout.CENTER);
		button.setIcon(null);
		button.setHorizontalTextPosition(SwingConstants.CENTER);
		button.setVerticalTextPosition(SwingConstants.CENTER);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setForeground(Color.WHITE);
		SidebarLayout.styleOutlinedButton(button, ColorScheme.LIGHT_GRAY_COLOR.darker(),
			compact ? 4 : 6, 6, compact ? 6 : 8, 6);
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusable(false);
		if (iconLabel != null)
		{
			button.addPropertyChangeListener("enabled", evt ->
				iconLabel.setIcon(button.isEnabled() ? packIconColor : packIconGray));
		}
		int bw = Math.max(96, buttonWidth);
		int minH = compact ? BOOSTER_MIN_HEIGHT_COMPACT : BOOSTER_BUTTON_MIN_HEIGHT;
		int neededH = Math.max(minH, button.getPreferredSize().height);
		Dimension tile = new Dimension(bw, neededH);
		button.setPreferredSize(tile);
		button.setMinimumSize(tile);
		button.setMaximumSize(tile);

		if (onBuy != null)
		{
			button.addActionListener(e -> onBuy.run());
		}
		return button;
	}

	/** Centered, white, small-font label used for the booster tile's title/price/progress text rows. */
	static JLabel shopBoosterTextLabel(String text)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER)
		{
			/** Re-applies the label style after a Look-and-Feel change resets it. */
			@Override
			public void updateUI()
			{
				super.updateUI();
				applyBoostBtnLabelStyle(this);
			}
		};
		applyBoostBtnLabelStyle(label);
		return label;
	}

	/** Applies the shared alignment/color/font styling for booster tile text labels. */
	private static void applyBoostBtnLabelStyle(JLabel label)
	{
		label.setAlignmentX(Component.CENTER_ALIGNMENT);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());
	}
}

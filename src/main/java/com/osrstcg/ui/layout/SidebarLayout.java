package com.osrstcg.ui.layout;

import com.formdev.flatlaf.FlatClientProperties;
import com.osrstcg.util.NumberFormatting;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

public final class SidebarLayout
{
	public static final int MAIN_PANEL_INSET = 6;
	public static final int TAB_BUTTON_GAP = 3;
	public static final int TAB_SCROLLBAR_WIDTH = 6;
	public static final int TAB_SCROLLBAR_GAP = 10;
	public static final int TAB_SCROLLBAR_RESERVED_WIDTH = TAB_SCROLLBAR_WIDTH + TAB_SCROLLBAR_GAP;
	public static final String PATREON_URL = "https://www.patreon.com/Azderi";
	public static final String DISCORD_URL = "https://discord.gg/P4pPu6RnCj";
	public static final String CREDITS_IMAGE_PATH = "/com/osrstcg/images/credits.png";

	private SidebarLayout()
	{
	}

	public static int sidebarInnerWidth()
	{
		return Math.max(160, PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET - TAB_SCROLLBAR_RESERVED_WIDTH);
	}

	public static void configureTabScrollPane(JScrollPane scrollPane)
	{
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);
		scrollPane.setWheelScrollingEnabled(true);
		scrollPane.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 1));

		JScrollBar vbar = scrollPane.getVerticalScrollBar();
		vbar.setUnitIncrement(16);
		vbar.setOpaque(false);
		vbar.putClientProperty(FlatClientProperties.STYLE,
			"width:" + TAB_SCROLLBAR_WIDTH + "; trackArc:999; thumbArc:999; trackInsets:0,2,0,2; thumbInsets:0,2,0,2; "
				+ "track:#00000000; thumb:#4D4D4D; hoverThumbColor:#787878; showButtons:false");

		scrollPane.addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && scrollPane.isShowing())
			{
				SwingUtilities.updateComponentTreeUI(vbar);
			}
		});
	}

	public static void revalidateTabScrollPane(JScrollPane scrollPane)
	{
		scrollPane.getViewport().revalidate();
		scrollPane.revalidate();
		scrollPane.repaint();
	}

	public static void initializeTabContentPanel(JPanel panel)
	{
		panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
	}

	public static JComponent tabRailWing()
	{
		JPanel wing = new JPanel();
		wing.setOpaque(false);
		wing.setPreferredSize(new Dimension(MAIN_PANEL_INSET, 1));
		wing.setMinimumSize(new Dimension(MAIN_PANEL_INSET, 1));
		return wing;
	}

	public static void stylePrimaryFooterButton(JButton button)
	{
		button.setFont(FontManager.getRunescapeBoldFont());
		button.setFocusable(false);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setForeground(Color.WHITE);
		styleOutlinedButton(button, ColorScheme.LIGHT_GRAY_COLOR.darker(), 10, 14, 10, 14);
	}

	public static void styleOutlinedButton(JComponent component, Color borderColor,
		int top, int left, int bottom, int right)
	{
		component.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, borderColor),
			new EmptyBorder(top, left, bottom, right)
		));
	}

	public static void lockFooterBlockHeight(JComponent block)
	{
		if (block == null || !block.isVisible())
		{
			return;
		}
		block.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.setMaximumSize(null);
		Dimension preferred = block.getPreferredSize();
		block.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(1, preferred.height)));
	}

	public static JComponent createTitleLinkButton(String imageClasspath, String tooltip, String url)
	{
		BufferedImage image = ImageUtil.loadImageResource(SidebarLayout.class, imageClasspath);
		if (image == null)
		{
			return null;
		}
		Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
		JLabel link = new JLabel(new ImageIcon(image));
		link.setBorder(BorderFactory.createEmptyBorder());
		link.setOpaque(false);
		link.setCursor(hand);
		link.setToolTipText(tooltip);
		link.setAlignmentY(Component.CENTER_ALIGNMENT);
		Dimension size = new Dimension(image.getWidth(), image.getHeight());
		link.setPreferredSize(size);
		link.setMinimumSize(size);
		link.setMaximumSize(size);
		link.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				link.setCursor(hand);
			}

			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					LinkBrowser.browse(url);
				}
			}
		});
		return link;
	}

	public static String format(long value)
	{
		return NumberFormatting.format(value);
	}

	public static String htmlEscape(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	public static String shorten(String value, int maxLen)
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

	public static void applySidebarStatLabelStyle(JLabel label)
	{
		label.setForeground(Color.WHITE);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
	}

	public static JLabel textPanel(String text)
	{
		JLabel label = new JLabel(text)
		{
			@Override
			public void updateUI()
			{
				super.updateUI();
				applySidebarStatLabelStyle(this);
			}
		};
		applySidebarStatLabelStyle(label);
		return label;
	}

	public static void clampPanelWidth(JPanel panel)
	{
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		Dimension preferred = panel.getPreferredSize();
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
	}

	public static void clampFixedWidth(JComponent component, int width)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		Dimension preferred = component.getPreferredSize();
		int h = preferred.height;
		component.setPreferredSize(new Dimension(width, h));
		component.setMaximumSize(new Dimension(width, h));
		component.setMinimumSize(new Dimension(0, h));
	}

	public static Font resolveWelcomeFont(boolean bold, int fontSize)
	{
		if (bold)
		{
			return FontManager.getRunescapeBoldFont();
		}
		if (fontSize >= 16)
		{
			return FontManager.getRunescapeFont();
		}
		return FontManager.getRunescapeSmallFont();
	}
}

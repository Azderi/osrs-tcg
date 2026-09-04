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
/**
 * Shared layout/styling helpers for the plugin sidebar panels: sizing constants, scroll pane and button
 * styling, text formatting utilities, and Swing width-clamping helpers used across the tab controllers.
 * All component-mutating methods must be called on the EDT.
 */
public final class SidebarLayout
{
	public static final int MAIN_PANEL_INSET = 6;
	public static final int TAB_BUTTON_GAP = 3;
	public static final int TAB_SCROLLBAR_THUMB = 6;
	public static final int TAB_SCROLLBAR_GAP = 10;
	public static final int TAB_SCROLLBAR_WIDTH = TAB_SCROLLBAR_THUMB + TAB_SCROLLBAR_GAP;
	public static final String PATREON_URL = "https://www.patreon.com/Azderi";
	public static final String DISCORD_URL = "https://discord.gg/P4pPu6RnCj";
	public static final String CREDITS_IMAGE_PATH = "/com/osrstcg/images/credits.png";

	private SidebarLayout()
	{
	}
/** Usable content width inside the RuneLite plugin panel, after its border and the tab scrollbar, floored at 160px. */
	public static int sidebarInnerWidth()
	{
		return Math.max(160, PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET - TAB_SCROLLBAR_WIDTH);
	}
/** Applies the sidebar's standard scroll pane chrome: no horizontal scroll, borderless/transparent, styled thin thumb. */
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
			"width:" + TAB_SCROLLBAR_THUMB + "; trackArc:999; thumbArc:999; trackInsets:0,2,0,2; thumbInsets:0,2,0,2; "
				+ "track:#00000000; thumb:#4D4D4D; hoverThumbColor:#787878; showButtons:false");

		scrollPane.addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && scrollPane.isShowing())
			{
				SwingUtilities.updateComponentTreeUI(vbar);
			}
		});
	}
/** Revalidates and repaints a tab scroll pane and its viewport after content changes size. */
	public static void revalidateTabScrollPane(JScrollPane scrollPane)
	{
		scrollPane.getViewport().revalidate();
		scrollPane.revalidate();
		scrollPane.repaint();
	}
/** Configures a tab's content panel as a transparent, left-aligned vertical stack. */
	public static void initializeTabContentPanel(JPanel panel)
	{
		panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
	}
/** Transparent fixed-width spacer used at each end of the tab rail to mirror {@link #MAIN_PANEL_INSET}. */
	public static JComponent tabRailWing()
	{
		JPanel wing = new JPanel();
		wing.setOpaque(false);
		wing.setPreferredSize(new Dimension(MAIN_PANEL_INSET, 1));
		wing.setMinimumSize(new Dimension(MAIN_PANEL_INSET, 1));
		return wing;
	}
/** Applies the sidebar's primary (bold, outlined, dark) footer button styling. */
	public static void stylePrimaryFooterButton(JButton button)
	{
		button.setFont(FontManager.getRunescapeBoldFont());
		button.setFocusable(false);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setForeground(Color.WHITE);
		styleOutlinedButton(button, ColorScheme.LIGHT_GRAY_COLOR.darker(), 10, 14, 10, 14);
	}
/** Sets a 1px matte border in {@code borderColor} with the given padding inside it. */
	public static void styleOutlinedButton(JComponent component, Color borderColor,
		int top, int left, int bottom, int right)
	{
		component.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, borderColor),
			new EmptyBorder(top, left, bottom, right)
		));
	}
/** Clamps a footer block's max height to its current preferred height, so it can't be stretched by its parent layout. No-op if invisible. */
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
/**
	 * Builds a clickable icon label that opens {@code url} in the default browser; returns {@code null}
	 * if the classpath image resource can't be loaded.
	 */
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
/** Re-asserts the hand cursor on re-entry. */
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				link.setCursor(hand);
			}
/** Opens {@code url} in the default browser on left-click. */
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
/** Delegates to {@link NumberFormatting#format(long)} for the sidebar's standard number display format. */
	public static String format(long value)
	{
		return NumberFormatting.format(value);
	}
/** Escapes {@code &amp;}, {@code &lt;}, {@code &gt;} for safe use inside a Swing HTML label; null becomes {@code ""}. */
	public static String htmlEscape(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
/** Truncates {@code value} to {@code maxLen} characters, appending "..." when it was longer (short limits truncate without ellipsis). */
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
/** Applies the sidebar's standard stat label styling: white, small font, vertically centered. */
	public static void applySidebarStatLabelStyle(JLabel label)
	{
		label.setForeground(Color.WHITE);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
	}
/** Builds a styled label that re-applies {@link #applySidebarStatLabelStyle} after any Look-and-Feel change. */
	public static JLabel textPanel(String text)
	{
		JLabel label = new JLabel(text)
		{
/** Re-applies the stat label style after a Look-and-Feel change resets it. */
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
/** Left-aligns the panel and clamps its max height to its current preferred height (unbounded width). */
	public static void clampPanelWidth(JPanel panel)
	{
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		Dimension preferred = panel.getPreferredSize();
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
	}
/** Left-aligns the component and pins its width to a fixed value, keeping its current preferred height. */
	public static void clampFixedWidth(JComponent component, int width)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		Dimension preferred = component.getPreferredSize();
		int h = preferred.height;
		component.setPreferredSize(new Dimension(width, h));
		component.setMaximumSize(new Dimension(width, h));
		component.setMinimumSize(new Dimension(0, h));
	}
/** Resolves the welcome panel's font: bold RuneScape font when {@code bold}, else regular or small by {@code fontSize} threshold. */
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

package com.osrstcg.ui.layout;

import com.osrstcg.cloud.api.CloudConnectionState;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.state.TcgStateService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
/** Title-row status dot and tab-rail paint for the plugin sidebar. */
public final class SidebarChrome
{
	private SidebarChrome()
	{
	}
/**
	 * Builds the small colored status dot shown in the sidebar title row. Its fill color and tooltip are
	 * driven later by {@link #updateCloudStatusIndicator} via client properties; starts red/"disconnected".
	 * Must be called on the EDT.
	 */
	public static JComponent createCloudStatusIndicator()
	{
		final Color liveGreen = new Color(0x2E, 0xC4, 0x5A);
		final Color connectingYellow = new Color(0xE0, 0xB0, 0x2E);
		final Color errorRed = new Color(0xE0, 0x4B, 0x4B);
		JComponent dot = new JComponent()
		{
/** Paints a filled circle using the color stashed in the {@code cloudIndicatorColor} client property. */
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				try
				{
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					int size = Math.min(getWidth(), getHeight());
					if (size < 3)
					{
						return;
					}
					int x = (getWidth() - size) / 2;
					int y = (getHeight() - size) / 2;
					Object colorObj = getClientProperty("cloudIndicatorColor");
					Color fill = colorObj instanceof Color ? (Color) colorObj : liveGreen;
					g2.setColor(fill);
					g2.fillOval(x, y, size, size);
				}
				finally
				{
					g2.dispose();
				}
			}
/** Fixed 8x8 dot size. */
			@Override
			public Dimension getPreferredSize()
			{
				return new Dimension(8, 8);
			}
/** Same as {@link #getPreferredSize()}; the dot never shrinks. */
			@Override
			public Dimension getMinimumSize()
			{
				return getPreferredSize();
			}
/** Same as {@link #getPreferredSize()}; the dot never grows. */
			@Override
			public Dimension getMaximumSize()
			{
				return getPreferredSize();
			}
		};
		dot.putClientProperty("cloudIndicatorColor", errorRed);
		dot.putClientProperty("cloudLiveGreen", liveGreen);
		dot.putClientProperty("cloudConnectingYellow", connectingYellow);
		dot.putClientProperty("cloudErrorRed", errorRed);
		dot.setOpaque(false);
		dot.setToolTipText("Cloud disconnected");
		return dot;
	}
/**
	 * Paints the horizontal divider under the tab rail: a full-width medium-gray line, with a dark-gray
	 * segment punched out under the currently active tab button so it reads as connected to its content
	 * below. Must be called from a component's {@code paintComponent}, on the EDT.
	 */
	public static void paintTabRailLine(JComponent strip, Graphics g, JButton active)
	{
		Color line = ColorScheme.MEDIUM_GRAY_COLOR;
		int y = strip.getHeight() - 1;
		if (y < 0 || strip.getWidth() <= 0)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setColor(line);
			g2.drawLine(0, y, strip.getWidth() - 1, y);

			if (active == null || !active.isShowing())
			{
				return;
			}
			Rectangle tabBounds = SwingUtilities.convertRectangle(
				active.getParent(), active.getBounds(), strip);
			g2.setColor(ColorScheme.DARK_GRAY_COLOR);
			g2.drawLine(tabBounds.x, y, tabBounds.x + tabBounds.width - 1, y);
		}
		finally
		{
			g2.dispose();
		}
	}
/**
	 * Resolves the cloud status dot's color and tooltip from current session state (account lock, RS login,
	 * restricted world, pending consent, connection state) in that priority order, then repaints it. Must
	 * be called on the EDT.
	 */
	public static void updateCloudStatusIndicator(
		JComponent cloudStatusIndicator,
		CloudSessionService cloudSessionService,
		TcgStateService stateService)
	{
		if (cloudStatusIndicator == null)
		{
			return;
		}
		CloudConnectionState state = cloudSessionService.getConnectionState();
		String message = cloudSessionService.getStatusMessage();
		boolean consentPending = cloudSessionService.needsCloudConsent();
		boolean restrictedWorld = cloudSessionService.isRestrictedWorld();
		boolean accountLocked = cloudSessionService.isAccountLocked();

		Color liveGreen = (Color) cloudStatusIndicator.getClientProperty("cloudLiveGreen");
		Color connectingYellow = (Color) cloudStatusIndicator.getClientProperty("cloudConnectingYellow");
		Color errorRed = (Color) cloudStatusIndicator.getClientProperty("cloudErrorRed");

		Color color;
		String tooltip;
		if (accountLocked)
		{
			color = errorRed;
			tooltip = message == null || message.isEmpty()
				? (cloudSessionService.isAccountBanned()
					? CloudSessionService.ACCOUNT_BANNED_STATUS
					: CloudSessionService.ACCOUNT_QUARANTINED_STATUS)
				: message;
		}
		else if (cloudSessionService.isRunescapeLoginRequired())
		{
			color = errorRed;
			tooltip = "Log in to RuneScape";
		}
		else if (restrictedWorld)
		{
			color = connectingYellow;
			tooltip = message == null || message.isEmpty()
				? "Credits disabled on this world type"
				: message;
		}
		else if (consentPending && state != CloudConnectionState.CONNECTING)
		{
			color = connectingYellow;
			tooltip = "Create an OSRS TCG profile to connect to cloud";
		}
		else
		{
			switch (state)
			{
				case CONNECTED:
					color = liveGreen;
					tooltip = message == null || message.isEmpty() ? "Cloud connected" : message;
					break;
				case CONNECTING:
					color = connectingYellow;
					tooltip = message == null || message.isEmpty() ? "Cloud connecting…" : message;
					break;
				case ERROR:
					color = errorRed;
					tooltip = message == null || message.isEmpty()
						? "Cloud error"
						: "Cloud error: " + message;
					break;
				case DISCONNECTED:
				default:
					color = errorRed;
					tooltip = message == null || message.isEmpty()
						? "Cloud disconnected"
						: "Cloud disconnected: " + message;
					break;
			}
		}
		cloudStatusIndicator.putClientProperty("cloudIndicatorColor", color);
		cloudStatusIndicator.setToolTipText(tooltip);
		cloudStatusIndicator.repaint();
	}
}

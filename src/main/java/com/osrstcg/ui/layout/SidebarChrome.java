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

	public static JComponent createCloudStatusIndicator()
	{
		final Color liveGreen = new Color(0x2E, 0xC4, 0x5A);
		final Color connectingYellow = new Color(0xE0, 0xB0, 0x2E);
		final Color errorRed = new Color(0xE0, 0x4B, 0x4B);
		JComponent dot = new JComponent()
		{
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

			@Override
			public Dimension getPreferredSize()
			{
				return new Dimension(8, 8);
			}

			@Override
			public Dimension getMinimumSize()
			{
				return getPreferredSize();
			}

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
		boolean migrationPending = cloudSessionService.isMigrationPending();
		boolean needsProfileCreate = cloudSessionService.needsProfileCreate();
		boolean restrictedWorld = cloudSessionService.isRestrictedWorld();
		boolean accountLocked = cloudSessionService.isAccountLocked();

		Color liveGreen = (Color) cloudStatusIndicator.getClientProperty("cloudLiveGreen");
		Color connectingYellow = (Color) cloudStatusIndicator.getClientProperty("cloudConnectingYellow");
		Color errorRed = (Color) cloudStatusIndicator.getClientProperty("cloudErrorRed");

		Color color;
		String tooltip;
		if (stateService.isDebugLogging())
		{
			color = connectingYellow;
			tooltip = "Cloud paused - debug mode";
		}
		else if (accountLocked)
		{
			color = errorRed;
			tooltip = message == null || message.isEmpty()
				? (cloudSessionService.isAccountBanned()
					? CloudSessionService.ACCOUNT_BANNED_STATUS
					: CloudSessionService.ACCOUNT_QUARANTINED_STATUS)
				: message;
		}
		else if (restrictedWorld)
		{
			color = connectingYellow;
			tooltip = message == null || message.isEmpty()
				? "Credits disabled on this world type"
				: message;
		}
		else if ((migrationPending || needsProfileCreate) && state != CloudConnectionState.CONNECTING)
		{
			color = connectingYellow;
			tooltip = migrationPending
				? "Migrate your local collection to reconnect to cloud"
				: "Create an OSRS TCG profile to connect to cloud";
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

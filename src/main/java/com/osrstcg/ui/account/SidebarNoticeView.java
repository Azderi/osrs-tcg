package com.osrstcg.ui.account;

import com.osrstcg.cloud.session.CloudSessionService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.GridBagLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.FontManager;
/**
 * Sidebar card that replaces the normal tab content with a centered message (event-world
 * unavailable, account locked/banned) and, when relevant, relocates the "open account panel"
 * button into it. Swing component; must be built and updated on the EDT.
 */
public final class SidebarNoticeView
{
/** CardLayout key this view is shown under. */
	public static final String CARD = "SIDEBAR_NOTICE";
	public static final String EVENT_WORLD_UNAVAILABLE = "OSRS TCG is not available on event worlds";

	private final JPanel sidebarNoticeContent = new JPanel(new BorderLayout(0, 0));
	private final JPanel sidebarNoticeMessageWrap = new JPanel(new GridBagLayout());
	private final JPanel sidebarNoticeButtonWrap = new JPanel(new BorderLayout(0, 0));
	private final JButton openAccountPanelButton;
	private final JPanel albumFooterWrap;
	private final CloudSessionService cloudSessionService;
	private final Runnable onManageAccountStateUpdate;
/**
	 * @param openAccountPanelButton shared button that gets reparented into this view when a notice needs it
	 * @param albumFooterWrap the button's normal home, to restore it to when the notice is dismissed
	 * @param onManageAccountStateUpdate invoked after the button is shown, to refresh its enabled state
	 */
	public SidebarNoticeView(
		JButton openAccountPanelButton,
		JPanel albumFooterWrap,
		CloudSessionService cloudSessionService,
		Runnable onManageAccountStateUpdate)
	{
		this.openAccountPanelButton = openAccountPanelButton;
		this.albumFooterWrap = albumFooterWrap;
		this.cloudSessionService = cloudSessionService;
		this.onManageAccountStateUpdate = onManageAccountStateUpdate;
		sidebarNoticeContent.setOpaque(false);
		sidebarNoticeMessageWrap.setOpaque(false);
		sidebarNoticeButtonWrap.setOpaque(false);
		sidebarNoticeButtonWrap.setBorder(new EmptyBorder(8, 0, 0, 0));
		sidebarNoticeContent.add(sidebarNoticeMessageWrap, BorderLayout.CENTER);
		sidebarNoticeContent.add(sidebarNoticeButtonWrap, BorderLayout.SOUTH);
	}
/** @return the root panel for this notice, to be added under {@link #CARD} in a CardLayout. */
	public JPanel content()
	{
		return sidebarNoticeContent;
	}
/** Shows the event-worlds-unavailable message, with no account panel button. */
	public void showEventWorldUnavailable(Runnable hideChrome)
	{
		showFullSidebarNotice(EVENT_WORLD_UNAVAILABLE, false, hideChrome);
	}
/** Shows the account-banned or account-quarantined message (per current session state), with the account panel button. */
	public void showAccountLockedNotice(Runnable hideChrome)
	{
		String message = cloudSessionService.isAccountBanned()
			? CloudSessionService.ACCOUNT_BANNED_STATUS
			: CloudSessionService.ACCOUNT_QUARANTINED_STATUS;
		showFullSidebarNotice(message, true, hideChrome);
	}
/**
	 * Renders the notice card: runs {@code hideChrome} to hide normal tab UI, sets the message text,
	 * and either reparents the account panel button into this view or restores it to the footer.
	 */
	public void showFullSidebarNotice(String messageText, boolean showAccountPanelButton, Runnable hideChrome)
	{
		hideChrome.run();

		sidebarNoticeMessageWrap.removeAll();
		JLabel message = new JLabel("<html><div style='text-align:center;width:180px'>"
			+ messageText
			+ "</div></html>");
		message.setForeground(Color.WHITE);
		message.setFont(FontManager.getRunescapeSmallFont());
		message.setHorizontalAlignment(SwingConstants.CENTER);
		sidebarNoticeMessageWrap.add(message);

		sidebarNoticeButtonWrap.removeAll();
		if (showAccountPanelButton)
		{
			reparentAccountPanelButton(sidebarNoticeButtonWrap);
			sidebarNoticeButtonWrap.setVisible(true);
			onManageAccountStateUpdate.run();
		}
		else
		{
			restoreAccountPanelToFooter();
			sidebarNoticeButtonWrap.setVisible(false);
		}

		sidebarNoticeContent.revalidate();
		sidebarNoticeContent.repaint();
	}
/** Moves the account panel button into {@code target} (a no-op if it's already there), revalidating both containers. */
	public void reparentAccountPanelButton(JPanel target)
	{
		if (target == null || openAccountPanelButton == null)
		{
			return;
		}
		Container parent = openAccountPanelButton.getParent();
		if (parent == target)
		{
			return;
		}
		if (parent != null)
		{
			parent.remove(openAccountPanelButton);
			parent.revalidate();
			parent.repaint();
		}
		target.add(openAccountPanelButton, BorderLayout.CENTER);
		target.revalidate();
		target.repaint();
	}
/** Moves the account panel button back to its normal footer location. */
	public void restoreAccountPanelToFooter()
	{
		if (openAccountPanelButton == null || albumFooterWrap == null)
		{
			return;
		}
		reparentAccountPanelButton(albumFooterWrap);
	}
}

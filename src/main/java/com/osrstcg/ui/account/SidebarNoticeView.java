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

public final class SidebarNoticeView
{
	public static final String CARD = "SIDEBAR_NOTICE";
	public static final String EVENT_WORLD_UNAVAILABLE = "OSRS TCG is not available on event worlds";

	private final JPanel sidebarNoticeContent = new JPanel(new BorderLayout(0, 0));
	private final JPanel sidebarNoticeMessageWrap = new JPanel(new GridBagLayout());
	private final JPanel sidebarNoticeButtonWrap = new JPanel(new BorderLayout(0, 0));
	private final JButton openAccountPanelButton;
	private final JPanel albumFooterWrap;
	private final CloudSessionService cloudSessionService;
	private final Runnable onManageAccountStateUpdate;

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

	public JPanel content()
	{
		return sidebarNoticeContent;
	}

	public void showEventWorldUnavailable(Runnable hideChrome)
	{
		showFullSidebarNotice(EVENT_WORLD_UNAVAILABLE, false, hideChrome);
	}

	public void showAccountLockedNotice(Runnable hideChrome)
	{
		String message = cloudSessionService.isAccountBanned()
			? CloudSessionService.ACCOUNT_BANNED_STATUS
			: CloudSessionService.ACCOUNT_QUARANTINED_STATUS;
		showFullSidebarNotice(message, true, hideChrome);
	}

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

	public void restoreAccountPanelToFooter()
	{
		if (openAccountPanelButton == null || albumFooterWrap == null)
		{
			return;
		}
		reparentAccountPanelButton(albumFooterWrap);
	}
}

package com.osrstcg.cloud.trade;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.util.Text;

/**
 * Message-row “TCG trade request” menu entries (friends / friends chat / clan).
 */
@Singleton
public class TcgTradeMenuHandler
{
	static final String TRADE_REQ_MENU_OPTION = "TCG trade request";

	private final Client client;
	private final TradeCloudService tradeCloudService;

	@Inject
	public TcgTradeMenuHandler(Client client, TradeCloudService tradeCloudService)
	{
		this.client = client;
		this.tradeCloudService = tradeCloudService;
	}

	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (event == null || !"Message".equals(event.getOption()))
		{
			return;
		}
		String target = event.getTarget();
		if (target == null || target.isEmpty())
		{
			return;
		}
		String playerName = Text.removeTags(target).trim();
		if (playerName.isEmpty())
		{
			return;
		}
		client.getMenu().createMenuEntry(0)
			.setOption(TRADE_REQ_MENU_OPTION)
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> tradeCloudService.sendTradeRequest(playerName));
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event == null || !TRADE_REQ_MENU_OPTION.equals(event.getMenuOption()))
		{
			return;
		}
		String target = event.getMenuTarget();
		if (target == null || target.isEmpty())
		{
			return;
		}
		String playerName = Text.removeTags(target).trim();
		if (!playerName.isEmpty())
		{
			tradeCloudService.sendTradeRequest(playerName);
		}
	}
}

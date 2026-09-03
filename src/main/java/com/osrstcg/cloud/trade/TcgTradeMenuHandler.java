package com.osrstcg.cloud.trade;

import com.osrstcg.OsrsTcgConfig;
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
	/** Menu option text injected on message rows and matched back on click. */
	static final String TRADE_REQ_MENU_OPTION = "TCG trade request";

	private final Client client;
	private final TradeCloudService tradeCloudService;
	private final OsrsTcgConfig config;

	@Inject
	public TcgTradeMenuHandler(Client client, TradeCloudService tradeCloudService, OsrsTcgConfig config)
	{
		this.client = client;
		this.tradeCloudService = tradeCloudService;
		this.config = config;
	}

	/**
	 * Injects a "TCG trade request" entry onto "Message" menu rows (friends / friends chat / clan). Must run
	 * on the client thread, as {@link MenuEntryAdded} handlers do.
	 */
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.friendsMenuOption())
		{
			return;
		}
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

	/**
	 * Handles clicks on the injected "TCG trade request" entry, sending the trade request for the clicked
	 * player. Must run on the client thread, as {@link MenuOptionClicked} handlers do; the actual request is
	 * dispatched asynchronously by {@link TradeCloudService#sendTradeRequest(String)}.
	 */
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

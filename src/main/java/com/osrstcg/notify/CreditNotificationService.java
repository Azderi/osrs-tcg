package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.util.TcgPluginGameMessages;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;
/**
 * Game-chat notifications when credit gains cross a booster pack purchase threshold.
 */
@Singleton
public class CreditNotificationService
{
	private final OsrsTcgConfig config;
	private final ChatMessageManager chatMessageManager;
	private final Notifier notifier;
/** Wires the config, chat, and RuneLite notifier used to announce the credit threshold. */
	@Inject
	CreditNotificationService(
		OsrsTcgConfig config,
		ChatMessageManager chatMessageManager,
		Notifier notifier)
	{
		this.config = config;
		this.chatMessageManager = chatMessageManager;
		this.notifier = notifier;
	}
/**
	 * Chats (and optionally shows a RuneLite notification) the first time credits cross the
	 * configured purchase-price threshold. No-op if credit notifications are disabled, credits
	 * didn't increase, the threshold is unset, or the threshold was already crossed before this gain.
	 */
	public void onCreditsIncreased(long creditsBefore, long creditsAfter)
	{
		if (!config.creditNotifications() || creditsAfter <= creditsBefore)
		{
			return;
		}

		int price = config.creditNotificationAmount();
		
		if (price <= 0 || creditsBefore >= price || creditsAfter < price)
		{
			return;
		}

		String message = "You now have " + price + " credits!";
		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, message);

		if (config.runeliteNotifications())
		{
			notifier.notify(message);
		}

	}
}

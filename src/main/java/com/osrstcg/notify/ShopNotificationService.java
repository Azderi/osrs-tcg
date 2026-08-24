package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.util.TcgPluginGameMessages;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;

/**
 * Game-chat notifications when credit gains cross a booster pack purchase threshold.
 */
@Singleton
public class ShopNotificationService
{
	private final OsrsTcgConfig config;
	private final PackCatalogService packCatalogService;
	private final ChatMessageManager chatMessageManager;
	private final Notifier notifier;

	@Inject
	ShopNotificationService(
		OsrsTcgConfig config,
		PackCatalogService packCatalogService,
		ChatMessageManager chatMessageManager,
		Notifier notifier)
	{
		this.config = config;
		this.packCatalogService = packCatalogService;
		this.chatMessageManager = chatMessageManager;
		this.notifier = notifier;
	}

	public void onCreditsIncreased(long creditsBefore, long creditsAfter)
	{
		if (!config.shopNotifications() || creditsAfter <= creditsBefore)
		{
			return;
		}

		for (BoosterPackDefinition booster : packCatalogService.getVisibleBoosters())
		{
			if (booster == null)
			{
				continue;
			}

			int price = booster.getPrice();
			if (price <= 0 || creditsBefore >= price || creditsAfter < price)
			{
				continue;
			}

			String message = "You have enough credits to purchase a " + packDisplayName(booster) + " booster pack!";
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, message);

			if (config.runeliteNotifications())
			{
				notifier.notify(message);
			}
		}
	}

	private static String packDisplayName(BoosterPackDefinition booster)
	{
		if (booster.getName() != null && !booster.getName().trim().isEmpty())
		{
			return booster.getName().trim();
		}
		if (booster.getId() != null && !booster.getId().trim().isEmpty())
		{
			return booster.getId().trim();
		}
		return "pack";
	}
}

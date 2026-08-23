package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.util.TcgPluginGameMessages;
import javax.inject.Inject;
import javax.inject.Singleton;
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

	@Inject
	ShopNotificationService(
		OsrsTcgConfig config,
		PackCatalogService packCatalogService,
		ChatMessageManager chatMessageManager)
	{
		this.config = config;
		this.packCatalogService = packCatalogService;
		this.chatMessageManager = chatMessageManager;
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

			TcgPluginGameMessages.queuePrefixedGameMessage(
				chatMessageManager,
				"You have enough credits to purchase a " + packDisplayName(booster) + " booster pack!");
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

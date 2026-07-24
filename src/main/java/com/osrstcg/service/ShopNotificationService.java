package com.osrstcg.service;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.data.BoosterPackDefinition;
import com.osrstcg.data.PackCatalog;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
	private final PackCatalog packCatalog;
	private final TcgStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final Notifier notifier;

	@Inject
	ShopNotificationService(
		OsrsTcgConfig config,
		PackCatalog packCatalog,
		TcgStateService stateService,
		ChatMessageManager chatMessageManager,
		Notifier notifier)
	{
		this.config = config;
		this.packCatalog = packCatalog;
		this.stateService = stateService;
		this.chatMessageManager = chatMessageManager;
		this.notifier = notifier;
	}

	public void onCreditsIncreased(long creditsBefore, long creditsAfter)
	{
		if (!config.shopNotifications() || creditsAfter <= creditsBefore)
		{
			return;
		}

		List<BoosterPackDefinition> boosters = new ArrayList<>(
			packCatalog.getVisibleBoosters(stateService.isDebugLogging()));
		boosters.sort(Comparator
			.comparingInt((BoosterPackDefinition b) -> b == null ? Integer.MAX_VALUE : b.getPrice())
			.thenComparing(b -> b == null || b.getName() == null ? "" : b.getName(),
				String.CASE_INSENSITIVE_ORDER));

		for (BoosterPackDefinition booster : boosters)
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

			String message = "You have enough credits to purchase " + packDisplayName(booster) + "!";
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
		return "a pack";
	}
}

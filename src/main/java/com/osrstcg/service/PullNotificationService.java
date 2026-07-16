package com.osrstcg.service;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.model.PullNotifyTier;
import com.osrstcg.party.TcgPullPartyMessage;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.party.PartyService;

/**
 * In-game chat (and optional Dink / party) notifications for notable pack pulls.
 */
@Slf4j
@Singleton
public class PullNotificationService
{
	private final OsrsTcgConfig config;
	private final Client client;
	private final ChatMessageManager chatMessageManager;
	private final CardDatabase cardDatabase;
	private final PartyService partyService;
	private final DinkNotificationService dinkNotificationService;
	private final PullWebhookNotificationService pullWebhookNotificationService;

	@Inject
	PullNotificationService(
		OsrsTcgConfig config,
		Client client,
		ChatMessageManager chatMessageManager,
		CardDatabase cardDatabase,
		PartyService partyService,
		DinkNotificationService dinkNotificationService,
		PullWebhookNotificationService pullWebhookNotificationService)
	{
		this.config = config;
		this.client = client;
		this.chatMessageManager = chatMessageManager;
		this.cardDatabase = cardDatabase;
		this.partyService = partyService;
		this.dinkNotificationService = dinkNotificationService;
		this.pullWebhookNotificationService = pullWebhookNotificationService;
	}

	public boolean shouldNotify(RarityMath.Tier tier, boolean foil, boolean newForCollection)
	{
		if (config.notifyNewCardsOnly() && !newForCollection && !(foil && config.notifyFoils()))
		{
			return false;
		}
		if (foil)
		{
			if (tier == null)
			{
				return config.notifyFoils();
			}
			PullNotifyTier minimum = config.notifyTier();
			RarityMath.Tier floor = minimum == null ? RarityMath.Tier.MYTHIC : minimum.toRarityTier();
			boolean tierOk = tier.ordinal() >= floor.ordinal();
			return tierOk || config.notifyFoils();
		}
		if (tier == null || !config.notifyNonFoils())
		{
			return false;
		}
		PullNotifyTier minimum = config.notifyTier();
		RarityMath.Tier floor = minimum == null ? RarityMath.Tier.MYTHIC : minimum.toRarityTier();
		return tier.ordinal() >= floor.ordinal();
	}

	public void notifyPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier)
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return;
		}
		if (cardName == null || cardName.trim().isEmpty() || !shouldNotify(tier, foil, newForCollection))
		{
			return;
		}
		String trimmed = cardName.trim();
		Color rarity = cardDatabase.chatRarityColorForCardName(trimmed);
		String formatted = TcgPluginGameMessages.formatPrefixedYouAddedCollection(trimmed, newForCollection, foil, rarity);
		String plain = TcgPluginGameMessages.plainPrefixedYouAddedCollection(trimmed, newForCollection, foil);
		TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);

		if (config.dinkNotifications())
		{
			dinkNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier);
		}

		pullWebhookNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier);
		log.debug(
			"Pull notification dispatched for '{}' (foil={}, new={}, tier={}, dink={}, webhookConfigured={})",
			trimmed,
			foil,
			newForCollection,
			tier == null ? "unknown" : tier.getLabel(),
			config.dinkNotifications(),
			isWebhookConfigured());

		if (config.partyAnnounceMythicPulls() && partyService.isInParty())
		{
			try
			{
				TcgPullPartyMessage message = new TcgPullPartyMessage();
				message.setCardName(trimmed);
				message.setNewForCollection(newForCollection);
				message.setFoil(foil);
				partyService.send(message);
			}
			catch (Exception ex)
			{
				log.debug("Could not send party pull message", ex);
			}
		}
	}

	private boolean isWebhookConfigured()
	{
		String url = config.pullWebhookUrl();
		return url != null && !url.trim().isEmpty();
	}
}

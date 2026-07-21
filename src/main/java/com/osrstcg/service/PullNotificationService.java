package com.osrstcg.service;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.model.DinkNotificationTrigger;
import com.osrstcg.model.PullNotifyTier;
import com.osrstcg.party.TcgPullPartyMessage;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
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
	private final ChatMessageManager chatMessageManager;
	private final CardDatabase cardDatabase;
	private final PartyService partyService;
	private final DinkNotificationService dinkNotificationService;
	private final PullWebhookNotificationService pullWebhookNotificationService;

	@Inject
	PullNotificationService(
		OsrsTcgConfig config,
		ChatMessageManager chatMessageManager,
		CardDatabase cardDatabase,
		PartyService partyService,
		DinkNotificationService dinkNotificationService,
		PullWebhookNotificationService pullWebhookNotificationService)
	{
		this.config = config;
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
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String trimmed = cardName.trim();
		boolean standardNotification = shouldNotify(tier, foil, newForCollection);
		if (standardNotification)
		{
			Color rarity = cardDatabase.chatRarityColorForCardName(trimmed);
			String formatted = TcgPluginGameMessages.formatPrefixedYouAddedCollection(trimmed, newForCollection, foil, rarity);
			String plain = TcgPluginGameMessages.plainPrefixedYouAddedCollection(trimmed, newForCollection, foil);
			TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
		}

		if (config.dinkNotifications() && dinkTrigger() == DinkNotificationTrigger.EVERY_CARD
			&& shouldNotifyDink(tier, foil, newForCollection))
		{
			dinkNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier);
		}

		if (standardNotification)
		{
			pullWebhookNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier);
			notifyParty(trimmed, newForCollection, foil);
		}
		log.debug(
			"Pull notification dispatched for '{}' (foil={}, new={}, tier={}, dink={}, webhookConfigured={})",
			trimmed,
			foil,
			newForCollection,
			tier == null ? "unknown" : tier.getLabel(),
			config.dinkNotifications(),
			isWebhookConfigured());

	}

	private void notifyParty(String cardName, boolean newForCollection, boolean foil)
	{
		if (config.partyAnnounceMythicPulls() && partyService.isInParty())
		{
			try
			{
				TcgPullPartyMessage message = new TcgPullPartyMessage();
				message.setCardName(cardName);
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

	public void notifyDinkAtEnd(List<PackRevealService.RevealCard> cards)
	{
		if (!config.dinkNotifications() || dinkTrigger() != DinkNotificationTrigger.AT_END
			|| cards == null || cards.isEmpty())
		{
			return;
		}
		List<DinkNotificationService.PackPull> eligiblePulls = new ArrayList<>();
		for (PackRevealService.RevealCard card : cards)
		{
			if (card == null || card.getPull() == null || card.getPull().getCardName() == null
				|| !shouldNotifyDink(card.getTier(), card.getPull().isFoil(), card.isNew()))
			{
				continue;
			}
			eligiblePulls.add(new DinkNotificationService.PackPull(
				card.getPull().getCardName().trim(), card.isNew(), card.getPull().isFoil(), card.getTier()));
		}
		dinkNotificationService.notifyPackSummary(eligiblePulls);
	}

	private boolean shouldNotifyDink(RarityMath.Tier tier, boolean foil, boolean newForCollection)
	{
		if (!newForCollection && config.dinkOnlyNotifyNew())
		{
			return false;
		}
		if (foil && config.dinkAlwaysNotifyFoils())
		{
			return true;
		}
		if (tier == null)
		{
			return false;
		}
		PullNotifyTier minimum = newForCollection
			? config.dinkNewCardNotifyTier()
			: config.dinkDuplicateNotifyTier();
		RarityMath.Tier floor = minimum == null ? RarityMath.Tier.MYTHIC : minimum.toRarityTier();
		return tier.ordinal() >= floor.ordinal();
	}

	private DinkNotificationTrigger dinkTrigger()
	{
		DinkNotificationTrigger trigger = config.dinkNotificationTrigger();
		return trigger == null ? DinkNotificationTrigger.EVERY_CARD : trigger;
	}

	private boolean isWebhookConfigured()
	{
		String url = config.pullWebhookUrl();
		return url != null && !url.trim().isEmpty();
	}
}

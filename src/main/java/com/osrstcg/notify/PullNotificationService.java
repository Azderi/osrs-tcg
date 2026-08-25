package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.config.DinkNotificationTrigger;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.config.PullNotifyTier;
import com.osrstcg.party.TcgPullPartyMessage;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.pack.PackRevealService.RevealCard;
import com.osrstcg.util.CardDisplayNames;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.party.PartyService;
import com.osrstcg.catalog.RarityMath;

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

	/**
	 * @return {@code true} when a collection-add line was queued to game chat
	 */
	public boolean notifyPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier)
	{
		return notifyPull(cardName, newForCollection, foil, tier, null);
	}

	/**
	 * @param instanceId pack-open card UUID for inspect links; may be null
	 * @return {@code true} when a collection-add line was queued to game chat
	 */
	public boolean notifyPull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return false;
		}
		String trimmed = cardName.trim();
		boolean standardNotification = shouldNotify(tier, foil, newForCollection);
		boolean chatPosted = false;
		if (standardNotification)
		{
			queueCollectionAddChat(trimmed, newForCollection, foil, cardDatabase.chatRarityColorForCardName(trimmed));
			chatPosted = true;
		}

		if (config.dinkNotifications() && dinkTrigger() == DinkNotificationTrigger.EVERY_CARD
			&& shouldNotifyDink(tier, foil, newForCollection))
		{
			dinkNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier, instanceId);
		}

		if (standardNotification)
		{
			pullWebhookNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier, instanceId);
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
		return chatPosted;
	}

	/**
	 * Always posts a collection-add chat line (ignores notify tier / new-only filters).
	 * Used when a pack reveal is quick-closed so every pulled card is listed.
	 */
	public void announceCollectionAddAlways(String cardName, boolean newForCollection, boolean foil, Color rarityColor)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String trimmed = cardName.trim();
		Color rarity = rarityColor != null ? rarityColor : cardDatabase.chatRarityColorForCardName(trimmed);
		queueCollectionAddChat(trimmed, newForCollection, foil, rarity);
	}

	/**
	 * Always posts collection-add chat for every pack-open pull (e.g. overlay already closed
	 * before pulls were bound into the reveal).
	 */
	public void announceAllCollectionAdds(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return;
		}
		Set<String> preOwnedKeys = new HashSet<>();
		if (preOwnedCards != null)
		{
			for (CardCollectionKey key : preOwnedCards)
			{
				if (key == null || key.getCardName() == null)
				{
					continue;
				}
				preOwnedKeys.add(normalizeOwnedKey(key.getCardName(), key.isFoil()));
			}
		}
		for (PackCardResult pull : pulls)
		{
			if (pull == null || pull.getCardName() == null || pull.getCardName().isBlank())
			{
				continue;
			}
			CardDefinition catalog = cardDatabase.findByName(pull.getCardName()).orElse(null);
			String name = CardDisplayNames.titleForPull(pull, catalog);
			boolean isNew = !preOwnedKeys.contains(normalizeOwnedKey(pull.getCardName(), pull.isFoil()));
			Color rarity = cardDatabase.chatRarityColorForCardName(name);
			announceCollectionAddAlways(name, isNew, pull.isFoil(), rarity);
		}
	}

	public void announceCollectionAddAlways(RevealCard card)
	{
		if (card == null || card.getPull() == null)
		{
			return;
		}
		PackCardResult pull = card.getPull();
		if (pull.getCardName() == null || pull.getCardName().isBlank())
		{
			return;
		}
		String name = CardDisplayNames.titleForDefinition(card.getDefinition(), pull);
		if (name == null || name.isBlank() || "Card".equals(name))
		{
			name = CardDisplayNames.titleForPull(pull, card.getDefinition());
		}
		if (name == null || name.isBlank())
		{
			return;
		}
		Color rarity = card.getRarityColor() != null
			? card.getRarityColor()
			: cardDatabase.chatRarityColorForCardName(name);
		announceCollectionAddAlways(name, card.isNew(), pull.isFoil(), rarity);
	}

	private void queueCollectionAddChat(String cardName, boolean newForCollection, boolean foil, Color rarityColor)
	{
		String formatted = TcgPluginGameMessages.formatPrefixedYouAddedCollection(
			cardName, newForCollection, foil, rarityColor);
		String plain = TcgPluginGameMessages.plainPrefixedYouAddedCollection(cardName, newForCollection, foil);
		TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
	}

	private static String normalizeOwnedKey(String cardName, boolean foil)
	{
		String name = cardName == null ? "" : cardName.trim().toLowerCase(Locale.ROOT);
		return name + "|" + (foil ? "1" : "0");
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
		List<DinkNotificationService.PackPull> pulls = new ArrayList<>();
		for (PackRevealService.RevealCard card : cards)
		{
			if (card == null || card.getPull() == null || card.getPull().getCardName() == null)
			{
				continue;
			}
			pulls.add(new DinkNotificationService.PackPull(
				card.getPull().getCardName().trim(),
				card.isNew(),
				card.getPull().isFoil(),
				card.getTier(),
				card.getPull().getInstanceId(),
				shouldNotifyDink(card.getTier(), card.getPull().isFoil(), card.isNew())));
		}
		dinkNotificationService.notifyPackSummary(pulls);
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

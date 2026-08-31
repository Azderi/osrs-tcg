package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.config.DinkNotificationTrigger;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.config.PullNotifyTier;
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
import com.osrstcg.catalog.RarityMath;

@Slf4j
@Singleton
public class PullNotificationService
{
	private final OsrsTcgConfig config;
	private final ChatMessageManager chatMessageManager;
	private final CardDatabase cardDatabase;
	private final DinkNotificationService dinkNotificationService;
	private final PullExternalNotificationService pullExternalNotificationService;

	@Inject
	PullNotificationService(
		OsrsTcgConfig config,
		ChatMessageManager chatMessageManager,
		CardDatabase cardDatabase,
		DinkNotificationService dinkNotificationService,
		PullExternalNotificationService pullExternalNotificationService)
	{
		this.config = config;
		this.chatMessageManager = chatMessageManager;
		this.cardDatabase = cardDatabase;
		this.dinkNotificationService = dinkNotificationService;
		this.pullExternalNotificationService = pullExternalNotificationService;
	}

	public boolean shouldNotify(RarityMath.Tier tier, boolean foil, boolean newForCollection)
	{
		return meetsNotifyRules(tier, foil, newForCollection, config.notifyTier());
	}

	private boolean shouldNotifyExternal(RarityMath.Tier tier, boolean foil, boolean newForCollection)
	{
		PullNotifyTier floor = newForCollection ? config.notifyTier() : config.duplicateNotifyTier();
		return meetsNotifyRules(tier, foil, newForCollection, floor);
	}

	private boolean meetsNotifyRules(
		RarityMath.Tier tier, boolean foil, boolean newForCollection, PullNotifyTier floor)
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
			return PullNotifySupport.meetsTier(tier, floor) || config.notifyFoils();
		}
		if (tier == null || !config.notifyNonFoils())
		{
			return false;
		}
		return PullNotifySupport.meetsTier(tier, floor);
	}

	public boolean notifyPull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return false;
		}
		String trimmed = cardName.trim();
		boolean chatNotify = shouldNotify(tier, foil, newForCollection);
		boolean externalNotify = shouldNotifyExternal(tier, foil, newForCollection);
		boolean chatPosted = false;
		if (chatNotify)
		{
			queueCollectionAddChat(trimmed, newForCollection, foil, cardDatabase.chatRarityColorForCardName(trimmed));
			chatPosted = true;
		}
		if (externalNotify)
		{
			pullExternalNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier, instanceId);
		}
		if (config.dinkNotifications() && dinkTrigger() == DinkNotificationTrigger.EVERY_CARD
			&& shouldNotifyDink(tier, foil, newForCollection))
		{
			dinkNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier, instanceId);
		}
		return chatPosted;
	}

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
		PullNotifyTier minimum = newForCollection
			? config.dinkNewCardNotifyTier()
			: config.dinkDuplicateNotifyTier();
		return PullNotifySupport.meetsTier(tier, minimum);
	}

	private DinkNotificationTrigger dinkTrigger()
	{
		DinkNotificationTrigger trigger = config.dinkNotificationTrigger();
		return trigger == null ? DinkNotificationTrigger.EVERY_CARD : trigger;
	}
}

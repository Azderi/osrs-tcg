package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.config.PullNotificationTrigger;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.pack.PackRevealService.RevealCard;
import com.osrstcg.util.CardDisplayNames;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.chat.ChatMessageManager;
import com.osrstcg.catalog.RarityMath;

@Singleton
public class PullNotificationService
{
	private final OsrsTcgConfig config;
	private final ChatMessageManager chatMessageManager;
	private final CardDatabase cardDatabase;
	private final PullNotifySupport pullNotifySupport;
	private final DinkNotificationService dinkNotificationService;
	private final PullExternalNotificationService externalNotifyService;

	@Inject
	PullNotificationService(
		OsrsTcgConfig config,
		ChatMessageManager chatMessageManager,
		CardDatabase cardDatabase,
		PullNotifySupport pullNotifySupport,
		DinkNotificationService dinkNotificationService,
		PullExternalNotificationService externalNotifyService)
	{
		this.config = config;
		this.chatMessageManager = chatMessageManager;
		this.cardDatabase = cardDatabase;
		this.pullNotifySupport = pullNotifySupport;
		this.dinkNotificationService = dinkNotificationService;
		this.externalNotifyService = externalNotifyService;
	}

	public boolean notifyPull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId)
	{
		if (PullNotificationMessages.isBlank(cardName) || !pullNotifySupport.shouldNotify(tier, foil, newForCollection))
		{
			return false;
		}
		String trimmed = cardName.trim();
		queueCollectionAddChat(trimmed, newForCollection, foil, cardDatabase.chatRarityColorForCardName(trimmed));
		externalNotifyService.notifyParty(trimmed, newForCollection, foil);
		if (pullNotifySupport.notificationTrigger() == PullNotificationTrigger.EVERY_CARD)
		{
			externalNotifyService.sendWebhook(trimmed, newForCollection, foil, tier, instanceId);
			if (config.dinkNotifications())
			{
				dinkNotificationService.notifyPackPull(trimmed, newForCollection, foil, tier, instanceId);
			}
		}
		return config.partyAnnouncePulls();
	}

	public void postCollectionAddChat(String cardName, boolean newForCollection, boolean foil, Color rarityColor)
	{
		if (PullNotificationMessages.isBlank(cardName))
		{
			return;
		}
		String trimmed = cardName.trim();
		Color rarity = rarityColor != null ? rarityColor : cardDatabase.chatRarityColorForCardName(trimmed);
		queueCollectionAddChat(trimmed, newForCollection, foil, rarity);
	}

	public void postAllCollectionAdds(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards)
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
			postCollectionAddChat(name, isNew, pull.isFoil(), rarity);
		}
	}

	public void postCollectionAddChat(RevealCard card)
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
		postCollectionAddChat(name, card.isNew(), pull.isFoil(), rarity);
	}

	public void notifyPackAtEnd(List<PackRevealService.RevealCard> cards)
	{
		if (pullNotifySupport.notificationTrigger() != PullNotificationTrigger.AT_END || cards == null || cards.isEmpty())
		{
			return;
		}
		pullNotifySupport.packSummaryContent(pullNotifySupport.packPullsFromCards(cards)).ifPresent(content ->
		{
			externalNotifyService.sendPackSummary(content);
			if (config.dinkNotifications())
			{
				dinkNotificationService.notifyPackSummary(content);
			}
		});
	}

	private void queueCollectionAddChat(String cardName, boolean newForCollection, boolean foil, Color rarityColor)
	{
		if (!config.partyAnnouncePulls())
		{
			return;
		}
		String formatted = TcgPluginGameMessages.formatYouAddedCollection(
			cardName, newForCollection, foil, rarityColor);
		String plain = TcgPluginGameMessages.plainYouAddedCollection(cardName, newForCollection, foil);
		TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
	}

	private static String normalizeOwnedKey(String cardName, boolean foil)
	{
		String name = cardName == null ? "" : cardName.trim().toLowerCase(Locale.ROOT);
		return name + "|" + (foil ? "1" : "0");
	}
}

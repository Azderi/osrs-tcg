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
/**
 * Entry point for pull/collection-add notifications: routes each pull to chat, party, Dink, and
 * webhook notifications according to config (per-card vs. end-of-pack summary, tier/foil filters).
 */
@Singleton
public class PullNotificationService
{
	private final OsrsTcgConfig config;
	private final ChatMessageManager chatMessageManager;
	private final CardDatabase cardDatabase;
	private final PullNotifySupport pullNotifySupport;
	private final DinkNotificationService dinkNotificationService;
	private final PullExternalNotificationService externalNotifyService;
/** Wires config, chat, the card database, the shared pull-content builder, and the Dink/webhook sub-services. */
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
/**
	 * Handles a single card pull: chats and party-broadcasts the collection add, then (if the pull
	 * meets the configured notify thresholds and the trigger is per-card) fires the webhook and Dink
	 * notifications immediately.
	 *
	 * @return true if party-announce is enabled, so callers know whether a party broadcast was sent
	 */
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
/** Queues the "you added" chat line for one card, resolving rarity color from the card database if not supplied. */
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
/**
	 * Chats a "you added" line for every pull in a batch (e.g. a full pack open), computing
	 * new-vs-duplicate per card against the pre-open owned-card snapshot.
	 */
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
/** Chats a "you added" line for a reveal-service card, deriving its display name and rarity color. */
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
/**
	 * Fires the end-of-pack webhook/Dink summary notification for a whole pack's pulls, when the
	 * notification trigger is set to end-of-pack rather than per-card.
	 */
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
/** Queues the formatted+plain "you added" chat message, if party-announce (which gates local chat too) is on. */
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
/** Builds a case-insensitive "name|foil" key for matching pulls against the pre-owned card snapshot. */
	private static String normalizeOwnedKey(String cardName, boolean foil)
	{
		String name = cardName == null ? "" : cardName.trim().toLowerCase(Locale.ROOT);
		return name + "|" + (foil ? "1" : "0");
	}
}

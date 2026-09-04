package com.osrstcg.party;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
/**
 * Handles incoming party messages from other members (pulls, set completions) and turns them into
 * local chat announcements. Ignores messages that originated from the local player.
 */
@Singleton
public class TcgPartyInboundHandler
{
	private final OsrsTcgConfig config;
	private final CardDatabase cardDatabase;
	private final PartyService partyService;
	private final ChatMessageManager chatMessageManager;
/** Wires config, the card database (for rarity color), the party service, and chat. */
	@Inject
	public TcgPartyInboundHandler(
		OsrsTcgConfig config,
		CardDatabase cardDatabase,
		PartyService partyService,
		ChatMessageManager chatMessageManager)
	{
		this.config = config;
		this.cardDatabase = cardDatabase;
		this.partyService = partyService;
		this.chatMessageManager = chatMessageManager;
	}
/**
	 * Chats a "so-and-so added X to their collection" line for a party member's pull. No-op if
	 * party-announce is off, the message/card name is blank, or the message came from the local player.
	 */
	public void onPull(TcgPullPartyMessage message)
	{
		if (!config.partyAnnouncePulls() || message == null)
		{
			return;
		}
		String cardName = message.getCardName();
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		if (isLocalMember(message.getMemberId()))
		{
			return;
		}
		String who = displayName(message.getMemberId());
		String trimmed = cardName.trim();
		Color rarity = cardDatabase.chatRarityColorForCardName(trimmed);
		String formatted = TcgPluginGameMessages.formatSomeoneAddedCollection(
			who, trimmed, message.isNewForCollection(), message.isFoil(), rarity);
		String plain = TcgPluginGameMessages.plainSomeoneAddedCollection(
			who, trimmed, message.isNewForCollection(), message.isFoil());
		TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
	}
/**
	 * Chats a "so-and-so just finished X!" line for a party member's set completion. No-op if
	 * party-announce is off, the message/collection name is blank, or the message came from the local player.
	 */
	public void onCollectionSetComplete(TcgCollectionSetCompletePartyMessage message)
	{
		if (!config.partyAnnouncePulls() || message == null)
		{
			return;
		}
		String collectionName = message.getCollectionName();
		if (collectionName == null || collectionName.trim().isEmpty())
		{
			return;
		}
		if (isLocalMember(message.getMemberId()))
		{
			return;
		}
		String who = displayName(message.getMemberId());
		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
			String.format(Locale.US, "%s just finished %s!", who, collectionName.trim()));
	}
/** True if {@code memberId} is the local player's own party member id. */
	private boolean isLocalMember(long memberId)
	{
		PartyMember localMember = partyService.getLocalMember();
		return localMember != null && memberId == localMember.getMemberId();
	}
/** Resolves a party member's display name for chat, falling back to "A party member" if unknown/blank. */
	private String displayName(long memberId)
	{
		PartyMember author = partyService.getMemberById(memberId);
		if (author != null && author.getDisplayName() != null && !author.getDisplayName().trim().isEmpty())
		{
			return author.getDisplayName().trim();
		}
		return "A party member";
	}
}

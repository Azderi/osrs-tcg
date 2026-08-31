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

@Singleton
public class TcgPartyInboundHandler
{
	private final OsrsTcgConfig config;
	private final CardDatabase cardDatabase;
	private final PartyService partyService;
	private final ChatMessageManager chatMessageManager;

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

	private boolean isLocalMember(long memberId)
	{
		PartyMember localMember = partyService.getLocalMember();
		return localMember != null && memberId == localMember.getMemberId();
	}

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

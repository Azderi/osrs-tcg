package com.runelitetcg.service;

import com.runelitetcg.RuneLiteTcgConfig;
import com.runelitetcg.model.TcgPublicStats;
import com.runelitetcg.party.TcgChatStatsPartyMessage;
import com.runelitetcg.party.TcgCollectionSetCompletePartyMessage;
import com.runelitetcg.party.TcgPullPartyMessage;
import com.runelitetcg.util.TcgPluginGameMessages;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.party.PartyService;

/**
 * Sends OSRS TCG party websocket payloads (Godly-tier pack reveals, collection set completion).
 */
@Slf4j
@Singleton
public class TcgPartyAnnouncer
{
	private final PartyService partyService;
	private final RuneLiteTcgConfig config;
	private final ChatMessageManager chatMessageManager;

	@Inject
	public TcgPartyAnnouncer(
		PartyService partyService,
		RuneLiteTcgConfig config,
		ChatMessageManager chatMessageManager)
	{
		this.partyService = partyService;
		this.config = config;
		this.chatMessageManager = chatMessageManager;
	}

	public void announceMythicPull(String cardName, boolean newForCollection, boolean foil)
	{
		if (!partyAnnouncementsEnabled())
		{
			return;
		}
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String label = TcgPluginGameMessages.announcedCardLabel(cardName, foil);
		String selfBody = newForCollection
			? String.format(Locale.US, "You just added %s to your collection!", label)
			: String.format(Locale.US, "You just pulled %s!", label);
		TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager, selfBody);

		if (!partyService.isInParty())
		{
			return;
		}
		try
		{
			TcgPullPartyMessage message = new TcgPullPartyMessage();
			message.setCardName(cardName.trim());
			message.setNewForCollection(newForCollection);
			message.setFoil(foil);
			partyService.send(message);
		}
		catch (Exception ex)
		{
			log.debug("Could not send Godly-tier party message", ex);
		}
	}

	public void announceCollectionSetComplete(String collectionDisplayName)
	{
		if (!partyAnnouncementsEnabled())
		{
			return;
		}
		if (collectionDisplayName == null || collectionDisplayName.trim().isEmpty())
		{
			return;
		}
		if (!partyService.isInParty())
		{
			return;
		}
		try
		{
			TcgCollectionSetCompletePartyMessage message = new TcgCollectionSetCompletePartyMessage();
			message.setCollectionName(collectionDisplayName.trim());
			partyService.send(message);
		}
		catch (Exception ex)
		{
			log.debug("Could not send collection set party message", ex);
		}
	}

	public void broadcastChatCommandStats(TcgPublicStats stats)
	{
		if (stats == null)
		{
			return;
		}
		if (!partyService.isInParty())
		{
			return;
		}
		try
		{
			TcgChatStatsPartyMessage message = new TcgChatStatsPartyMessage();
			message.setCollectionScore(stats.getCollectionScore());
			message.setCompletionPct(stats.getCompletionPct());
			message.setUniqueOwned(stats.getUniqueOwned());
			message.setTotalCardPool(stats.getTotalCardPool());
			message.setOpenedPacks(stats.getOpenedPacks());
			message.setTotalCardsOwned(stats.getTotalCardsOwned());
			partyService.send(message);
		}
		catch (Exception ex)
		{
			log.debug("Could not send !tcg stats party message", ex);
		}
	}

	private boolean partyAnnouncementsEnabled()
	{
		return config.partyAnnounceMythicPulls();
	}
}

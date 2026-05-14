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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
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
	private final Client client;
	private final ClientThread clientThread;

	@Inject
	public TcgPartyAnnouncer(
		PartyService partyService,
		RuneLiteTcgConfig config,
		Client client,
		ClientThread clientThread)
	{
		this.partyService = partyService;
		this.config = config;
		this.client = client;
		this.clientThread = clientThread;
	}

	public void announceMythicPull(String cardName, boolean newForCollection)
	{
		if (!partyAnnouncementsEnabled())
		{
			return;
		}
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String c = cardName.trim();
		String selfBody = newForCollection
			? String.format(Locale.US, "You just added '%s' to your collection!", c)
			: String.format(Locale.US, "You just pulled %s!", c);
		String selfLine = TcgPluginGameMessages.withGoldPluginPrefix(selfBody);
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", selfLine, null));

		if (!partyService.isInParty())
		{
			return;
		}
		try
		{
			TcgPullPartyMessage message = new TcgPullPartyMessage();
			message.setCardName(c);
			message.setNewForCollection(newForCollection);
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

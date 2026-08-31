package com.osrstcg.party;

import com.osrstcg.OsrsTcgConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.party.PartyService;

@Slf4j
@Singleton
public class TcgPartyAnnouncer
{
	private final PartyService partyService;
	private final OsrsTcgConfig config;

	@Inject
	public TcgPartyAnnouncer(PartyService partyService, OsrsTcgConfig config)
	{
		this.partyService = partyService;
		this.config = config;
	}

	public void announceSetComplete(String collectionDisplayName)
	{
		if (!config.partyAnnouncePulls())
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

}

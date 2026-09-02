package com.osrstcg.party;

import com.osrstcg.OsrsTcgConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.party.PartyService;

/** Broadcasts collection/set-completion events to the current RuneLite party. */
@Slf4j
@Singleton
public class TcgPartyAnnouncer
{
	private final PartyService partyService;
	private final OsrsTcgConfig config;

	/** Wires the party service and config used to gate and send announcements. */
	@Inject
	public TcgPartyAnnouncer(PartyService partyService, OsrsTcgConfig config)
	{
		this.partyService = partyService;
		this.config = config;
	}

	/**
	 * Sends a {@link TcgCollectionSetCompletePartyMessage} to the party. No-op if party-announce is
	 * disabled, the name is blank, or not currently in a party.
	 */
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

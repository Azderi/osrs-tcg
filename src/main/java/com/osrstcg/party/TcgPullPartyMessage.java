package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Party websocket payload: a party member pulled a card, for the other members' inbound handlers
 * to chat as a collection-add announcement.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgPullPartyMessage extends PartyMemberMessage
{
	/** Display name of the pulled card. */
	private String cardName;
	/** True if this was a new card for the puller's collection, false if a duplicate. */
	private boolean newForCollection;
	/** True if the pulled copy is foil. */
	private boolean foil;
}

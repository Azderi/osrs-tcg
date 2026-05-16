package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Party websocket payload: a party member completed every card in a primary-category set (roll pool).
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgCollectionSetCompletePartyMessage extends PartyMemberMessage
{
	private String collectionName;
}

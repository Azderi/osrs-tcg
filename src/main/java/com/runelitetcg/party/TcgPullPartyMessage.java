package com.runelitetcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Party websocket payload: a party member revealed a Godly-tier card from a pack.
 * Registered with {@link net.runelite.client.party.WSClient#registerMessage(Class)} so Gson can deserialize it.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgPullPartyMessage extends PartyMemberMessage
{
	private String cardName;
	/** True if this card was not owned (any variant) before the pack that contained it. */
	private boolean newForCollection;
}

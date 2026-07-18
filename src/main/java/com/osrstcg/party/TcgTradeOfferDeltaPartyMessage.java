package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Add or remove one offered card in an active trade. Any delta clears ready on both clients locally.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeOfferDeltaPartyMessage extends PartyMemberMessage
{
	private String tradeId;
	/** {@code true} = add to sender's offer side; {@code false} = remove. */
	private boolean add;
	private String cardInstanceId;
	private String cardName;
	private boolean foil;
	private String pulledByUsername;
	private long pulledAtEpochMs;
}

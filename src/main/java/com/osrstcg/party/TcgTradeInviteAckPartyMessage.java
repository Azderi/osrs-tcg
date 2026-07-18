package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Recipient confirms a {@link TcgTradeInvitePartyMessage} was received (parity checks passed and the invite is pending).
 * The sender treats a missing ack as a failed send.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeInviteAckPartyMessage extends PartyMemberMessage
{
	private String tradeId;
}

package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Marks the sender ready (or not) to complete the trade. Both must be ready before the commit leader sends
 * {@link TcgTradeCommitPartyMessage}.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeReadyPartyMessage extends PartyMemberMessage
{
	private String tradeId;
	private boolean ready;
}

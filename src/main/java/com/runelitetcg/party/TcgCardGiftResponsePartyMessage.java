package com.runelitetcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Recipient answers a {@link TcgCardGiftPartyMessage}: accepted after adding the card, or rejected when multiplier
 * settings do not match (sender keeps the card).
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgCardGiftResponsePartyMessage extends PartyMemberMessage
{
	private String transferId;
	private long originalSenderMemberId;
	private boolean accepted;
}

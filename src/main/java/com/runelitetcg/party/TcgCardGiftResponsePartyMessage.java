package com.runelitetcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Recipient answers a {@link TcgCardGiftPartyMessage}: accepted after adding the card, or rejected when multiplier /
 * debug settings do not match (sender keeps the card). When {@link #accepted} is false, {@link #rejectCode} is
 * non-zero: {@code 1} tuning mismatch, {@code 2} debug mismatch, {@code 3} sender plugin too old (missing debug
 * field), {@code 4} bad payload.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgCardGiftResponsePartyMessage extends PartyMemberMessage
{
	private String transferId;
	private long originalSenderMemberId;
	private boolean accepted;
	/** Non-zero when {@link #accepted} is false; see class-level javadoc for values. */
	private int rejectCode;
}

package com.runelitetcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Sender offers one card copy to a specific party member. Recipient must match {@link #foilChancePercent} and
 * multipliers before adding the card and confirming. Provenance is carried in {@link #pulledByUsername} /
 * {@link #pulledAtEpochMs}; {@link #cardInstanceId} is the sender's row id and is removed only after accept.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgCardGiftPartyMessage extends PartyMemberMessage
{
	private long recipientMemberId;
	private String cardName;
	private boolean foil;
	/** Sender's collection row id; removed on successful accept. */
	private String cardInstanceId;
	private String pulledByUsername;
	private long pulledAtEpochMs;
	private int foilChancePercent;
	private double killCreditMultiplier;
	private double levelUpCreditMultiplier;
	private double xpCreditMultiplier;
	private String transferId;
}

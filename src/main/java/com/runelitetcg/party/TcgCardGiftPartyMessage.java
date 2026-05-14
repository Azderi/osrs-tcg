package com.runelitetcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Sender offers one card copy to a specific party member. Recipient must match {@link #foilChancePercent} and
 * multipliers before adding the card and confirming.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgCardGiftPartyMessage extends PartyMemberMessage
{
	private long recipientMemberId;
	private String cardName;
	private boolean foil;
	private int foilChancePercent;
	private double killCreditMultiplier;
	private double levelUpCreditMultiplier;
	private double xpCreditMultiplier;
	private String transferId;
}

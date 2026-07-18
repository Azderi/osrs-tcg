package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Sender invites a party member to open a two-sided trade. Recipient validates multiplier parity and Overview debug
 * mode before showing Accept in the album. {@link #senderDebugLogging} must be non-null on the wire.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeInvitePartyMessage extends PartyMemberMessage
{
	private String tradeId;
	private long recipientMemberId;
	private int foilChancePercent;
	private double killCreditMultiplier;
	private double levelUpCreditMultiplier;
	private double xpCreditMultiplier;
	/** Sender's Overview debug toggle; must equal recipient's or the invite is rejected ({@code null} = legacy). */
	private Boolean senderDebugLogging;
}

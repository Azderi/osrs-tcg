package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Recipient answers a {@link TcgTradeInvitePartyMessage}. When {@link #accepted} is false, {@link #rejectCode} is
 * non-zero: {@code 1} tuning mismatch, {@code 2} debug mismatch, {@code 3} sender plugin too old (missing debug
 * field), {@code 4} bad payload, {@code 5} busy (already in a trade/invite).
 * <p>
 * When accepted, foil/credit multipliers and {@link #responderDebugLogging} must be present so the invite sender can
 * verify settings parity (same rules as party card gifts).
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeInviteResponsePartyMessage extends PartyMemberMessage
{
	private String tradeId;
	private long originalSenderMemberId;
	private boolean accepted;
	/** Non-zero when {@link #accepted} is false; see class-level javadoc for values. */
	private int rejectCode;
	private int foilChancePercent;
	private double killCreditMultiplier;
	private double levelUpCreditMultiplier;
	private double xpCreditMultiplier;
	/** Acceptor's Overview debug toggle when {@link #accepted}; must match invite sender ({@code null} = legacy). */
	private Boolean responderDebugLogging;
}

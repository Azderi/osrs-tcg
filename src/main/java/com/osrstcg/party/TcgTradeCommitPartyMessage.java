package com.osrstcg.party;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Commit leader (lower party member id) finalizes the trade. Both sides verify the snapshot against their session and
 * confirm foil/credit/debug parity (same rules as party card gifts), then remove local offers and add the partner's cards.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeCommitPartyMessage extends PartyMemberMessage
{
	private String tradeId;
	/** Cards the commit sender is giving away. */
	private List<TcgTradeOfferCardDto> committerOffers = new ArrayList<>();
	/** Cards the commit sender expects to receive (partner's offers). */
	private List<TcgTradeOfferCardDto> partnerOffers = new ArrayList<>();
	private int foilChancePercent;
	private double killCreditMultiplier;
	private double levelUpCreditMultiplier;
	private double xpCreditMultiplier;
	/** Committer's Overview debug toggle; must match the follower ({@code null} = reject). */
	private Boolean committerDebugLogging;
}

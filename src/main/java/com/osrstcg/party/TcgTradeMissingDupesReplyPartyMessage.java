package com.osrstcg.party;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Reply to {@link TcgTradeMissingDupesQueryPartyMessage}: subset of queried variants the local player does not own.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeMissingDupesReplyPartyMessage extends PartyMemberMessage
{
	private String tradeId;
	private List<TcgTradeCardVariantDto> missingVariants = new ArrayList<>();
}

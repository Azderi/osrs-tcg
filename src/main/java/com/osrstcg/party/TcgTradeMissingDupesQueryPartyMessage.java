package com.osrstcg.party;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * During an active trade, sender asks which of their unlocked duplicate variants the partner does not own.
 * Does not include instance ids or full collection data.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeMissingDupesQueryPartyMessage extends PartyMemberMessage
{
	private String tradeId;
	private List<TcgTradeCardVariantDto> variants = new ArrayList<>();
}

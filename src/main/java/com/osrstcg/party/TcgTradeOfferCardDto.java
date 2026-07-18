package com.osrstcg.party;

import lombok.Data;

/**
 * One card copy offered in a party trade (wire payload for deltas and commit snapshots).
 */
@Data
public class TcgTradeOfferCardDto
{
	private String cardInstanceId;
	private String cardName;
	private boolean foil;
	private String pulledByUsername;
	private long pulledAtEpochMs;
}

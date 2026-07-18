package com.osrstcg.party;

import lombok.Data;

/**
 * Card name + foil flag only (no instance ids). Used to ask which duplicate variants a trade partner is missing.
 */
@Data
public class TcgTradeCardVariantDto
{
	private String cardName;
	private boolean foil;
}

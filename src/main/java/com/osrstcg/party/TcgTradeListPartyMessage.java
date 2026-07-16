package com.osrstcg.party;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Opt-in party sync for trade discovery. Contains only duplicate card variants the sender can offer, not their full
 * collection.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgTradeListPartyMessage extends PartyMemberMessage
{
	private int schemaVersion;
	private long sentAtEpochMs;
	private boolean senderDebugLogging;
	private int foilChancePercent;
	private double killCreditMultiplier;
	private double levelUpCreditMultiplier;
	private double xpCreditMultiplier;
	private List<Entry> duplicates;

	@Data
	public static class Entry
	{
		private String cardName;
		private int normalAvailable;
		private int foilAvailable;
	}
}

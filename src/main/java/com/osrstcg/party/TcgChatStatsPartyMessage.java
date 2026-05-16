package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Party sync for {@code !tcg} chat command: recipients cache the sender's public collection stats.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class TcgChatStatsPartyMessage extends PartyMemberMessage
{
	private long collectionScore;
	private double completionPct;
	private int uniqueOwned;
	private int totalCardPool;
	private long openedPacks;
	private int totalCardsOwned;
}

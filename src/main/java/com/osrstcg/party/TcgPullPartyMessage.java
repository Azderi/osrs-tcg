package com.osrstcg.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.runelite.client.party.messages.PartyMemberMessage;

@Data
@EqualsAndHashCode(callSuper = false)
public class TcgPullPartyMessage extends PartyMemberMessage
{
	private String cardName;
	private boolean newForCollection;
	private boolean foil;
}

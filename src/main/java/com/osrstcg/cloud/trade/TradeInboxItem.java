package com.osrstcg.cloud.trade;

/**
 * Pending incoming trade for sidebar Accept button + chat ping.
 */
public final class TradeInboxItem
{
	private final String tradeId;
	private final String fromDisplayName;
	private final boolean notified;

	/** @param fromDisplayName null is normalized to an empty string */
	public TradeInboxItem(String tradeId, String fromDisplayName, boolean notified)
	{
		this.tradeId = tradeId;
		this.fromDisplayName = fromDisplayName == null ? "" : fromDisplayName;
		this.notified = notified;
	}

	/** Server-assigned id of the pending trade. */
	public String getTradeId()
	{
		return tradeId;
	}

	/** Display name of the player who sent the trade request; never null. */
	public String getFromDisplayName()
	{
		return fromDisplayName;
	}

	/** Whether the chat ping for this trade has already been shown. */
	public boolean isNotified()
	{
		return notified;
	}
}

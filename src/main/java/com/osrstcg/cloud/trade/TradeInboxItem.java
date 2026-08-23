package com.osrstcg.cloud.trade;

/**
 * Pending incoming trade for sidebar Accept button + chat ping.
 */
public final class TradeInboxItem
{
	private final String tradeId;
	private final String fromDisplayName;
	private final boolean notified;

	public TradeInboxItem(String tradeId, String fromDisplayName, boolean notified)
	{
		this.tradeId = tradeId;
		this.fromDisplayName = fromDisplayName == null ? "" : fromDisplayName;
		this.notified = notified;
	}

	public String getTradeId()
	{
		return tradeId;
	}

	public String getFromDisplayName()
	{
		return fromDisplayName;
	}

	public boolean isNotified()
	{
		return notified;
	}
}

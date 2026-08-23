package com.osrstcg.state;

import lombok.Value;

@Value
public class CardSellResult
{
	boolean success;
	String message;
	int cardsSold;
	long creditsBefore;
	long creditsAfter;

	public static CardSellResult failed(String message, long creditsBefore)
	{
		return new CardSellResult(false, message == null ? "" : message, 0, creditsBefore, creditsBefore);
	}

	public static CardSellResult succeeded(String message, int cardsSold, long creditsBefore, long creditsAfter)
	{
		return new CardSellResult(true, message == null ? "" : message, Math.max(0, cardsSold),
			creditsBefore, creditsAfter);
	}
}

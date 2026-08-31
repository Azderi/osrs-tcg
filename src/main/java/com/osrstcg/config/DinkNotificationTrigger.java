package com.osrstcg.config;

public enum DinkNotificationTrigger
{
	EVERY_CARD("Every card"),
	AT_END("At end");

	private final String label;

	DinkNotificationTrigger(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

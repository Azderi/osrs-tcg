package com.osrstcg.config;

public enum PullNotificationTrigger
{
	EVERY_CARD("Every card"),
	AT_END("At end");

	private final String label;

	PullNotificationTrigger(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

package com.osrstcg.config;
/** Config option choices for when pack-pull notifications are fired: per card or once at the end of the pack. */
public enum PullNotificationTrigger
{
	EVERY_CARD("Every card"),
	AT_END("At end");

	private final String label;
/** @param label display label shown in config UI. */
	PullNotificationTrigger(String label)
	{
		this.label = label;
	}
/** @return display label shown in config UI. */
	@Override
	public String toString()
	{
		return label;
	}
}

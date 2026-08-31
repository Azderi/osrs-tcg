package com.osrstcg.config;

public enum CreditsPerHourWindow
{
	MINUTES_15("15 min", 15L * 60L * 1000L),
	MINUTES_30("30 min", 30L * 60L * 1000L),
	HOUR_1("1 hour", 60L * 60L * 1000L),
	PERSISTENT("Persistent", null);

	private final String label;
	/** {@code null} when history never auto-expires. */
	private final Long windowMs;

	CreditsPerHourWindow(String label, Long windowMs)
	{
		this.label = label;
		this.windowMs = windowMs;
	}

	public Long getWindowMs()
	{
		return windowMs;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

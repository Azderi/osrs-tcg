package com.osrstcg.config;

/**
 * Sliding window for credits/h on the credits infobox.
 * {@link #PERSISTENT} keeps all gains until a manual overlay reset.
 */
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

	/**
	 * @return window length in ms, or {@code null} for {@link #PERSISTENT}
	 */
	public Long getWindowMs()
	{
		return windowMs;
	}

	public boolean isPersistent()
	{
		return windowMs == null;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

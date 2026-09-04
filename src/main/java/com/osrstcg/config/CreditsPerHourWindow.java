package com.osrstcg.config;
/** Config option choices for the rolling window used to compute a credits-per-hour rate. */
public enum CreditsPerHourWindow
{
	MINUTES_15("15 min", 15L * 60L * 1000L),
	MINUTES_30("30 min", 30L * 60L * 1000L),
	HOUR_1("1 hour", 60L * 60L * 1000L),
	PERSISTENT("Persistent", null);

	private final String label;
/** {@code null} when history never auto-expires. */
	private final Long windowMs;
/**
	 * @param label display label shown in config UI.
	 * @param windowMs window length in ms, or {@code null} for persistent (never expires).
	 */
	CreditsPerHourWindow(String label, Long windowMs)
	{
		this.label = label;
		this.windowMs = windowMs;
	}
/** @return window length in ms, or {@code null} when history never auto-expires. */
	public Long getWindowMs()
	{
		return windowMs;
	}
/** @return display label shown in config UI. */
	@Override
	public String toString()
	{
		return label;
	}
}

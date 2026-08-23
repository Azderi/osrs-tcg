package com.osrstcg.cloud.activity;

/** Result of {@code GET} activities config. */
public final class ActivitiesConfigResponse
{
	private final boolean notModified;
	private final ActivityConfigDto body;

	private ActivitiesConfigResponse(boolean notModified, ActivityConfigDto body)
	{
		this.notModified = notModified;
		this.body = body;
	}

	public static ActivitiesConfigResponse notModified()
	{
		return new ActivitiesConfigResponse(true, null);
	}

	public static ActivitiesConfigResponse ok(ActivityConfigDto body)
	{
		return new ActivitiesConfigResponse(false, body);
	}

	public boolean isNotModified()
	{
		return notModified;
	}

	public ActivityConfigDto getBody()
	{
		return body;
	}
}

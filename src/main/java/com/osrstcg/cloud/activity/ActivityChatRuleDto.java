package com.osrstcg.cloud.activity;

/**
 * One chat-message activity rule from {@code GET /api/v1/config/activities}.
 */
public final class ActivityChatRuleDto
{
	public String activityId;
	/** {@code prefix} or {@code regex}. */
	public String match;
	public String value;
	public long credits;
	public String label;
}

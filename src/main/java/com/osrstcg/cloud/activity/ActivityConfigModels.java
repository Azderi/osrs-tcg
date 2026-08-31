package com.osrstcg.cloud.activity;

import java.util.ArrayList;
import java.util.List;

public final class ActivityConfigModels
{
	private ActivityConfigModels()
	{
	}

	public static final class ActivityConfigDto
	{
		public String version;
		public List<ActivityChatRuleDto> chatRules = new ArrayList<>();
		public NpcExclusionsDto npcExclusions = new NpcExclusionsDto();
	}

	public static final class ActivityChatRuleDto
	{
		public String activityId;
		/** {@code prefix} or {@code regex}. */
		public String match;
		public String value;
		public long credits;
		public String label;
	}

	public static final class NpcExclusionsDto
	{
		public List<Integer> npcIds = new ArrayList<>();
		/** Inclusive {@code [lo, hi]} pairs. */
		public List<List<Integer>> npcIdRanges = new ArrayList<>();
	}

	public static final class ActivitiesConfigResponse
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
}

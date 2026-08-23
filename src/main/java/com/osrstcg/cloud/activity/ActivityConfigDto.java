package com.osrstcg.cloud.activity;

import java.util.ArrayList;
import java.util.List;

/**
 * Full body from {@code GET /api/v1/config/activities}.
 */
public final class ActivityConfigDto
{
	public String version;
	public List<ActivityChatRuleDto> chatRules = new ArrayList<>();
	public NpcExclusionsDto npcExclusions = new NpcExclusionsDto();
}

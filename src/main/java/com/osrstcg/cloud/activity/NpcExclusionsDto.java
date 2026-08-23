package com.osrstcg.cloud.activity;

import java.util.ArrayList;
import java.util.List;

/**
 * NPC kill exclusions from {@code GET /api/v1/config/activities} (IDs / ranges only).
 */
public final class NpcExclusionsDto
{
	public List<Integer> npcIds = new ArrayList<>();
	/** Inclusive {@code [lo, hi]} pairs. */
	public List<List<Integer>> npcIdRanges = new ArrayList<>();
}

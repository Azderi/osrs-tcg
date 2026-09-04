package com.osrstcg.cloud.activity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw Gson DTOs for the server's activity-config JSON payload, plus the response wrapper for the
 * conditional-GET fetch. {@link ActivityConfigService} compiles these into {@link CompiledActivityConfig}.
 */
public final class ActivityConfigModels
{
	/** Namespace only; not instantiable. */
	private ActivityConfigModels()
	{
	}

	/** Top-level activity config payload as returned by the cloud API or read from the disk cache. */
	public static final class ActivityConfigDto
	{
		/** Opaque version/ETag string used for change detection. */
		public String version;
		public List<ActivityChatRuleDto> chatRules = new ArrayList<>();
		public NpcExclusionsDto npcExclusions = new NpcExclusionsDto();
		/** Per-NPC-id optimistic kill credit multipliers; JSON keys are NPC id strings. */
		public Map<String, KillCreditMultiplierDto> killCreditMultipliers = new HashMap<>();
	}

	/** Multiplier applied to combat-level optimistic credits for a specific NPC id. */
	public static final class KillCreditMultiplierDto
	{
		public double multiplier;
	}

	/** One chat-message-to-credits rule, matched by literal prefix or regex against a chat line. */
	public static final class ActivityChatRuleDto
	{
		public String activityId;
		/** {@code prefix} or {@code regex}. */
		public String match;
		public String value;
		public long credits;
		public String label;
	}

	/** NPC ids excluded from credit-earning activities, given as individual ids and/or inclusive ranges. */
	public static final class NpcExclusionsDto
	{
		public List<Integer> npcIds = new ArrayList<>();
		/** Inclusive {@code [lo, hi]} pairs. */
		public List<List<Integer>> npcIdRanges = new ArrayList<>();
	}

	/** Result of a conditional-GET activity config fetch: either "unchanged" (304) or a fresh body. */
	public static final class ActivitiesConfigResponse
	{
		private final boolean notModified;
		private final ActivityConfigDto body;

		private ActivitiesConfigResponse(boolean notModified, ActivityConfigDto body)
		{
			this.notModified = notModified;
			this.body = body;
		}

		/** Server reported the client's cached version is still current; no new body was returned. */
		public static ActivitiesConfigResponse notModified()
		{
			return new ActivitiesConfigResponse(true, null);
		}

		/** Server returned a fresh config body to replace the cached one. */
		public static ActivitiesConfigResponse ok(ActivityConfigDto body)
		{
			return new ActivitiesConfigResponse(false, body);
		}

		/** True if the server responded 304 Not Modified and {@link #getBody()} is null. */
		public boolean isNotModified()
		{
			return notModified;
		}

		/** The fresh config body, or null when {@link #isNotModified()} is true. */
		public ActivityConfigDto getBody()
		{
			return body;
		}
	}
}

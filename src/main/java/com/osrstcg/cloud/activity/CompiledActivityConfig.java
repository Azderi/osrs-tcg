package com.osrstcg.cloud.activity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
/**
 * Immutable, thread-safe snapshot of activity config for chat matching, NPC id exclusions,
 * per-NPC kill credit multipliers, and region-gated XP rules.
 */
public final class CompiledActivityConfig
{
/** Empty config used before any fetch/disk-cache load has succeeded. */
	public static final CompiledActivityConfig EMPTY = new CompiledActivityConfig(
		"",
		List.of(),
		Set.of(),
		Map.of(),
		List.of());

	private final String version;
	private final List<CompiledChatRule> chatRules;
	private final Set<Integer> excludedNpcIds;
	private final Map<Integer, Double> killCreditMultipliers;
	private final List<CompiledXpRegionRule> xpRegionRules;
/** Normalizes nulls to empty and defensively copies the collections into immutable ones. */
	CompiledActivityConfig(
		String version,
		List<CompiledChatRule> chatRules,
		Set<Integer> excludedNpcIds,
		Map<Integer, Double> killCreditMultipliers,
		List<CompiledXpRegionRule> xpRegionRules)
	{
		this.version = version == null ? "" : version;
		this.chatRules = chatRules == null ? List.of() : List.copyOf(chatRules);
		this.excludedNpcIds = excludedNpcIds == null ? Set.of() : Set.copyOf(excludedNpcIds);
		this.killCreditMultipliers = killCreditMultipliers == null ? Map.of() : Map.copyOf(killCreditMultipliers);
		this.xpRegionRules = xpRegionRules == null ? List.of() : List.copyOf(xpRegionRules);
	}
/** Opaque version string this config was compiled from; empty for {@link #EMPTY}. */
	public String getVersion()
	{
		return version;
	}

	public List<CompiledChatRule> getChatRules()
	{
		return chatRules;
	}
/** Whether {@code npcId} is excluded from credit-earning activities. */
	public boolean isExcludedNpc(int npcId)
	{
		return excludedNpcIds.contains(npcId);
	}
/**
	 * Optimistic kill-credit multiplier for {@code npcId}; defaults to {@code 1.0} when unset.
	 */
	public double getKillCreditMultiplier(int npcId)
	{
		Double multiplier = killCreditMultipliers.get(npcId);
		return multiplier == null ? 1.0 : multiplier;
	}

	public List<CompiledXpRegionRule> getXpRegionRules()
	{
		return xpRegionRules;
	}
/** First region-gated XP rule covering {@code skill} in map region {@code regionId}, or {@code null} if none. */
	public CompiledXpRegionRule findXpRegionRule(Skill skill, int regionId)
	{
		if (skill == null)
		{
			return null;
		}
		for (CompiledXpRegionRule rule : xpRegionRules)
		{
			if (rule.matches(skill, regionId))
			{
				return rule;
			}
		}
		return null;
	}
/** Precompiled region-gated XP rule: credits {@code skill} XP gained inside any of {@code regionIds}. */
	public static final class CompiledXpRegionRule
	{
		private final String activityId;
		private final Skill skill;
		private final Set<Integer> regionIds;
		private final long xpPerChunk;
		private final long creditsPerChunk;
		private final String label;
/** Normalizes nulls and clamps credits to non-negative; callers must supply a non-null skill, non-empty regions and a positive chunk size. */
		CompiledXpRegionRule(
			String activityId,
			Skill skill,
			Set<Integer> regionIds,
			long xpPerChunk,
			long creditsPerChunk,
			String label)
		{
			this.activityId = activityId == null ? "" : activityId;
			this.skill = skill;
			this.regionIds = regionIds == null ? Set.of() : Set.copyOf(regionIds);
			this.xpPerChunk = Math.max(1L, xpPerChunk);
			this.creditsPerChunk = Math.max(0L, creditsPerChunk);
			this.label = label == null ? "" : label;
		}
/** True if this rule credits {@code skill} XP while the player is in map region {@code regionId}. */
		public boolean matches(Skill skill, int regionId)
		{
			return this.skill == skill && regionIds.contains(regionId);
		}

		public String getActivityId()
		{
			return activityId;
		}

		public Skill getSkill()
		{
			return skill;
		}

		public Set<Integer> getRegionIds()
		{
			return regionIds;
		}

		public long getXpPerChunk()
		{
			return xpPerChunk;
		}

		public long getCreditsPerChunk()
		{
			return creditsPerChunk;
		}

		public String getLabel()
		{
			return label;
		}
	}
/** Precompiled chat-message match rule: literal prefix or regex, mutually exclusive. */
	public static final class CompiledChatRule
	{
		private final String activityId;
		private final long credits;
		private final String label;
		private final String prefix;
		private final Pattern pattern;
/** Normalizes nulls and clamps credits to non-negative; exactly one of {@code prefix}/{@code pattern} is expected. */
		CompiledChatRule(String activityId, long credits, String label, String prefix, Pattern pattern)
		{
			this.activityId = activityId == null ? "" : activityId;
			this.credits = Math.max(0L, credits);
			this.label = label == null ? "" : label;
			this.prefix = prefix;
			this.pattern = pattern;
		}
/** True if {@code message} satisfies this rule's regex or prefix match. */
		public boolean matches(String message)
		{
			if (message == null)
			{
				return false;
			}
			if (pattern != null)
			{
				return pattern.matcher(message).matches();
			}
			return prefix != null && message.startsWith(prefix);
		}

		public String getActivityId()
		{
			return activityId;
		}

		public long getCredits()
		{
			return credits;
		}

		public String getLabel()
		{
			return label;
		}
	}
}

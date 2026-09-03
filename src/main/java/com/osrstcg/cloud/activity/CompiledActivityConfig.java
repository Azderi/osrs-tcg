package com.osrstcg.cloud.activity;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, thread-safe snapshot of activity config for chat matching and NPC id exclusions.
 */
public final class CompiledActivityConfig
{
	/** Empty config used before any fetch/disk-cache load has succeeded. */
	public static final CompiledActivityConfig EMPTY = new CompiledActivityConfig(
		"",
		List.of(),
		Set.of());

	private final String version;
	private final List<CompiledChatRule> chatRules;
	private final Set<Integer> excludedNpcIds;

	/** Normalizes nulls to empty and defensively copies the collections into immutable ones. */
	CompiledActivityConfig(
		String version,
		List<CompiledChatRule> chatRules,
		Set<Integer> excludedNpcIds)
	{
		this.version = version == null ? "" : version;
		this.chatRules = chatRules == null ? List.of() : List.copyOf(chatRules);
		this.excludedNpcIds = excludedNpcIds == null ? Set.of() : Set.copyOf(excludedNpcIds);
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

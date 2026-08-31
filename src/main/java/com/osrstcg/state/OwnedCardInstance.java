package com.osrstcg.state;

import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

@Getter
public final class OwnedCardInstance
{
	/** Prefix on pulledBy for debug give/pulls. */
	public static final String DEBUG_PULL_METADATA_PREFIX = "DEBUG_";

	private final String instanceId;
	private final String cardName;
	private final boolean foil;
	private final String pulledByUsername;
	private final long pulledAtEpochMs;
	private final boolean locked;
	private final Double condition;
	private final boolean beta;
	private final String source;

	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs)
	{
		this(instanceId, cardName, foil, pulledByUsername, pulledAtEpochMs, false, null, false, null);
	}

	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs, boolean locked)
	{
		this(instanceId, cardName, foil, pulledByUsername, pulledAtEpochMs, locked, null, false, null);
	}

	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs, boolean locked, Double condition, boolean beta, String source)
	{
		this.instanceId = instanceId == null || instanceId.isEmpty()
			? UUID.randomUUID().toString()
			: instanceId;
		this.cardName = cardName == null ? "" : cardName;
		this.foil = foil;
		this.pulledByUsername = pulledByUsername == null ? "" : pulledByUsername;
		this.pulledAtEpochMs = Math.max(0L, pulledAtEpochMs);
		this.locked = locked;
		this.condition = normalizeCondition(condition);
		this.beta = beta;
		this.source = source == null || source.isBlank() ? null : source.trim();
	}

	public OwnedCardInstance withLocked(boolean nextLocked)
	{
		if (locked == nextLocked)
		{
			return this;
		}
		return new OwnedCardInstance(instanceId, cardName, foil, pulledByUsername, pulledAtEpochMs, nextLocked,
			condition, beta, source);
	}

	public static OwnedCardInstance createNew(String cardName, boolean foil, String pulledByUsername, long pulledAtEpochMs)
	{
		return new OwnedCardInstance(UUID.randomUUID().toString(), cardName, foil, pulledByUsername, pulledAtEpochMs);
	}

	public static boolean hasDebugPullMetadata(String pulledByUsername)
	{
		return pulledByUsername != null && pulledByUsername.startsWith(DEBUG_PULL_METADATA_PREFIX);
	}

	public static String withDebugPullMetadataPrefix(String playerNameOrSanitized)
	{
		if (playerNameOrSanitized == null)
		{
			return DEBUG_PULL_METADATA_PREFIX;
		}
		String t = playerNameOrSanitized.trim();
		if (t.startsWith(DEBUG_PULL_METADATA_PREFIX))
		{
			return t;
		}
		return DEBUG_PULL_METADATA_PREFIX + t;
	}

	private static Double normalizeCondition(Double value)
	{
		if (value == null || Double.isNaN(value) || Double.isInfinite(value))
		{
			return null;
		}
		double clamped = Math.max(0.01d, Math.min(100.0d, value));
		return clamped;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof OwnedCardInstance))
		{
			return false;
		}
		OwnedCardInstance that = (OwnedCardInstance) o;
		return Objects.equals(instanceId, that.instanceId);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(instanceId);
	}
}

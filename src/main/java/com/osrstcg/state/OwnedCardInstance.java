package com.osrstcg.state;

import java.util.Objects;
import java.util.UUID;

/**
 * One physical copy of a card in the collection (normal or foil), with provenance for album tooltips and party trades.
 */
public final class OwnedCardInstance
{
	/**
	 * Prefix on {@link #pulledByUsername} for cards from {@code ::tcg-give}, free debug booster pulls, or any pack opened
	 * while Overview debug logging is enabled.
	 */
	public static final String DEBUG_PULL_METADATA_PREFIX = "DEBUG_";

	private final String instanceId;
	private final String cardName;
	private final boolean foil;
	private final String pulledByUsername;
	private final long pulledAtEpochMs;
	private final boolean locked;
	/** Optional wear 0.01–100 from cloud; null when absent (typical for beta). */
	private final Double condition;
	/** True for migrated beta copies; omitted/false for normal cards. */
	private final boolean beta;
	/** Origin label from cloud. */
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

	public String getInstanceId()
	{
		return instanceId;
	}

	public String getCardName()
	{
		return cardName;
	}

	public boolean isFoil()
	{
		return foil;
	}

	public String getPulledByUsername()
	{
		return pulledByUsername;
	}

	public long getPulledAtEpochMs()
	{
		return pulledAtEpochMs;
	}

	public boolean isLocked()
	{
		return locked;
	}

	public Double getCondition()
	{
		return condition;
	}

	public boolean isBeta()
	{
		return beta;
	}

	public String getSource()
	{
		return source;
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

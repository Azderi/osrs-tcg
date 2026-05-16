package com.osrstcg.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One physical copy of a card in the collection (normal or foil), with provenance for album tooltips and party trades.
 */
public final class OwnedCardInstance
{
	/**
	 * Prefix on {@link #pulledByUsername} for cards from {@code ::tcg-give}, free debug booster pulls, or any pack opened
	 * while Overview debug logging is enabled. Album tooltips show this as {@code Debug_}{@code username} via
	 * {@link #formatPulledByForUi(String)}.
	 */
	public static final String DEBUG_PULL_METADATA_PREFIX = "DEBUG_";

	private final String instanceId;
	private final String cardName;
	private final boolean foil;
	private final String pulledByUsername;
	private final long pulledAtEpochMs;

	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs)
	{
		this.instanceId = instanceId == null || instanceId.isEmpty()
			? UUID.randomUUID().toString()
			: instanceId;
		this.cardName = cardName == null ? "" : cardName;
		this.foil = foil;
		this.pulledByUsername = pulledByUsername == null ? "" : pulledByUsername;
		this.pulledAtEpochMs = Math.max(0L, pulledAtEpochMs);
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

	/**
	 * Provenance line for UI: debug-tagged rows use {@code Debug_}{@code username}; storage keeps {@link #DEBUG_PULL_METADATA_PREFIX}.
	 */
	public static String formatPulledByForUi(String pulledByUsername)
	{
		if (pulledByUsername == null || pulledByUsername.trim().isEmpty())
		{
			return "";
		}
		String raw = pulledByUsername.trim();
		if (!hasDebugPullMetadata(raw))
		{
			return raw;
		}
		return "Debug_" + raw.substring(DEBUG_PULL_METADATA_PREFIX.length());
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

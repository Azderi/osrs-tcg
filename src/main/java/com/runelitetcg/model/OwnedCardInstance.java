package com.runelitetcg.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One physical copy of a card in the collection (normal or foil), with provenance for album tooltips and party trades.
 */
public final class OwnedCardInstance
{
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

package com.osrstcg.state;

import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
/**
 * One physical owned copy of a card, tracked individually (rather than as a bare quantity) so
 * pull attribution/timestamp and foil/beta status survive per-copy. Identity is by
 * {@link #instanceId} alone.
 */
@Getter
public final class OwnedCardInstance
{
	private final String instanceId;
	private final String cardName;
	private final boolean foil;
	private final String pulledByUsername;
	private final long pulledAtEpochMs;
	private final boolean beta;
/** Non-beta convenience overload; delegates with {@code beta = false}. */
	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs)
	{
		this(instanceId, cardName, foil, pulledByUsername, pulledAtEpochMs, false);
	}
/** Generates a random {@code instanceId} when null/empty; normalizes other fields to non-null/non-negative. */
	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs, boolean beta)
	{
		this.instanceId = instanceId == null || instanceId.isEmpty()
			? UUID.randomUUID().toString()
			: instanceId;
		this.cardName = cardName == null ? "" : cardName;
		this.foil = foil;
		this.pulledByUsername = pulledByUsername == null ? "" : pulledByUsername;
		this.pulledAtEpochMs = Math.max(0L, pulledAtEpochMs);
		this.beta = beta;
	}
/** Equal when {@link #instanceId} matches; other fields are not compared. */
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

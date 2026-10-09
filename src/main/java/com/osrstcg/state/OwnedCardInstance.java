package com.osrstcg.state;

import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
/** Owned card copy; identity is {@link #instanceId}. {@code temp} = unsettled pack-open placeholder. */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class OwnedCardInstance
{
	@EqualsAndHashCode.Include
	private final String instanceId;
	private final String cardName;
	private final boolean foil;
	private final String pulledByUsername;
	private final long pulledAtEpochMs;
	private final boolean temp;

	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs)
	{
		this(instanceId, cardName, foil, pulledByUsername, pulledAtEpochMs, false);
	}

	public OwnedCardInstance(String instanceId, String cardName, boolean foil, String pulledByUsername,
		long pulledAtEpochMs, boolean temp)
	{
		this.instanceId = instanceId == null || instanceId.isEmpty() ? UUID.randomUUID().toString() : instanceId;
		this.cardName = cardName == null ? "" : cardName;
		this.foil = foil;
		this.pulledByUsername = pulledByUsername == null ? "" : pulledByUsername;
		this.pulledAtEpochMs = Math.max(0L, pulledAtEpochMs);
		this.temp = temp;
	}
}

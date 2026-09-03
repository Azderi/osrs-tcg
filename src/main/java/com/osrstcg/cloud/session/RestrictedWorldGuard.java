package com.osrstcg.cloud.session;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.WorldType;

/**
 * Detects world types (PvP arena, Deadman, seasonal, etc.) where cloud credits/attests must be
 * disabled, and describes which blocked types are active on the current world.
 */
@Singleton
public final class RestrictedWorldGuard
{
	private static final Set<WorldType> BLOCKED = EnumSet.of(
		WorldType.PVP_ARENA,
		WorldType.DEADMAN,
		WorldType.SEASONAL,
		WorldType.TOURNAMENT_WORLD,
		WorldType.QUEST_SPEEDRUNNING,
		WorldType.NOSAVE_MODE,
		WorldType.BETA_WORLD);

	public static final String STATUS_MESSAGE = "Credits disabled on this world type";

	private final Client client;

	@Inject
	RestrictedWorldGuard(Client client)
	{
		this.client = client;
	}

	/** Whether the current world (per the client) has a blocked world type. */
	public boolean isRestricted()
	{
		return isRestricted(client == null ? null : client.getWorldType());
	}

	/** Whether any of {@code types} is a blocked world type. */
	public static boolean isRestricted(EnumSet<WorldType> types)
	{
		if (types == null || types.isEmpty())
		{
			return false;
		}
		for (WorldType blocked : BLOCKED)
		{
			if (types.contains(blocked))
			{
				return true;
			}
		}
		return false;
	}

	/** Comma-joined names of the blocked world types present on the current world, or {@code ""} if none. */
	public String describeBlockedTypes()
	{
		EnumSet<WorldType> types = client == null ? null : client.getWorldType();
		if (types == null || types.isEmpty())
		{
			return "";
		}
		return types.stream()
			.filter(BLOCKED::contains)
			.map(Enum::name)
			.collect(Collectors.joining(", "));
	}
}

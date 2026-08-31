package com.osrstcg.cloud.session;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.WorldType;

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

	public boolean isRestricted()
	{
		return isRestricted(client == null ? null : client.getWorldType());
	}

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

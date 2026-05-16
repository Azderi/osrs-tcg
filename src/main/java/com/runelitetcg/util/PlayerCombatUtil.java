package com.runelitetcg.util;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;

/**
 * Detects whether the local player is in active combat: attacking a player/NPC or being targeted by one.
 */
public final class PlayerCombatUtil
{
	private PlayerCombatUtil()
	{
	}

	public static boolean isLocalPlayerInCombat(Client client)
	{
		if (client == null)
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return false;
		}

		Actor target = local.getInteracting();
		if (target != null && isCombatTarget(target))
		{
			return true;
		}

		for (NPC npc : client.getNpcs())
		{
			if (npc != null && npc.getInteracting() == local)
			{
				return true;
			}
		}

		for (Player other : client.getPlayers())
		{
			if (other != null && other != local && other.getInteracting() == local)
			{
				return true;
			}
		}

		return false;
	}

	private static boolean isCombatTarget(Actor actor)
	{
		return actor instanceof NPC || actor instanceof Player;
	}
}

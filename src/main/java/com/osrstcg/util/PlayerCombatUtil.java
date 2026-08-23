package com.osrstcg.util;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;

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

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView != null)
		{
			for (NPC npc : worldView.npcs())
			{
				if (npc != null && !PetNpcIds.isPetNpc(npc) && npc.getInteracting() == local)
				{
					return true;
				}
			}

			for (Player other : worldView.players())
			{
				if (other != null && other != local && other.getInteracting() == local)
				{
					return true;
				}
			}
		}

		return false;
	}

	public static boolean isCombatTarget(Actor actor)
	{
		if (actor instanceof Player)
		{
			return true;
		}
		if (actor instanceof NPC)
		{
			return !PetNpcIds.isPetNpc((NPC) actor);
		}
		return false;
	}
}

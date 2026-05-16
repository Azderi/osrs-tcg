package com.osrstcg.service;

import com.osrstcg.util.PlayerCombatUtil;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks whether the local player is in combat for Safe-mode pack restrictions.
 */
@Singleton
public final class PlayerCombatMonitor
{
	private static final int INCOMING_DAMAGE_GRACE_TICKS = 3;

	private final Client client;
	private volatile boolean localPlayerInCombat;
	private int incomingDamageGraceUntilTick = -1;

	@Inject
	public PlayerCombatMonitor(Client client)
	{
		this.client = client;
	}

	public boolean isLocalPlayerInCombat()
	{
		return localPlayerInCombat;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client == null)
		{
			localPlayerInCombat = false;
			incomingDamageGraceUntilTick = -1;
			return;
		}
		int tick = client.getTickCount();
		boolean grace = incomingDamageGraceUntilTick >= tick;
		localPlayerInCombat = grace || PlayerCombatUtil.isLocalPlayerInCombat(client);
		if (!localPlayerInCombat)
		{
			incomingDamageGraceUntilTick = -1;
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return;
		}
		if (event.getSource() == client.getLocalPlayer() && isCombatTarget(event.getTarget()))
		{
			localPlayerInCombat = true;
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return;
		}
		if (event.getActor() != client.getLocalPlayer() || event.getHitsplat() == null)
		{
			return;
		}
		if (event.getHitsplat().isMine())
		{
			return;
		}
		incomingDamageGraceUntilTick = client.getTickCount() + INCOMING_DAMAGE_GRACE_TICKS;
		localPlayerInCombat = true;
	}

	public void reset()
	{
		localPlayerInCombat = false;
		incomingDamageGraceUntilTick = -1;
	}

	private static boolean isCombatTarget(Actor actor)
	{
		return actor instanceof NPC || actor instanceof Player;
	}
}

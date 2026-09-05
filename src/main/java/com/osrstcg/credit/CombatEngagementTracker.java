package com.osrstcg.credit;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;
/**
 * Tracks whether the local player is, or very recently was, in combat so that XP from non-combat spells
 * (alchemy, enchanting, superheat, teleports, ...) can be told apart from combat-spell XP. Combat is
 * inferred from three signals: the player targeting another actor (or being targeted), hitsplats dealt by
 * or applied to the player, and Hitpoints XP gains reported by {@link CreditAwardService}. Splashing is
 * treated as combat because the player is interacting with the target throughout. Subscribes to
 * {@link InteractingChanged}, {@link HitsplatApplied} and {@link GameStateChanged}.
 */
@Singleton
public final class CombatEngagementTracker
{
	private final Client client;
/** Whether any combat signal has been seen since the last {@link #reset()}. */
	private boolean combatSeen;
/** Game tick of the most recent combat signal; only meaningful when {@link #combatSeen} is true. */
	private int lastCombatTick;

	@Inject
	public CombatEngagementTracker(Client client)
	{
		this.client = client;
	}
/**
	 * Whether the player should currently be treated as in combat: true if they are interacting with any
	 * actor right now, or if a combat signal was seen within the last {@code lockoutTicks} game ticks.
	 */
	public boolean isInCombat(int lockoutTicks)
	{
		if (client == null)
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		if (local != null && local.getInteracting() != null)
		{
			return true;
		}
		return withinLockout(combatSeen, lastCombatTick, client.getTickCount(), lockoutTicks);
	}
/** Records a Hitpoints XP gain as a combat signal (Hitpoints XP only comes from dealing damage). */
	public void noteHitpointsXpGain()
	{
		markCombat();
	}
/** Forgets all combat signals, e.g. on logout or world hop. */
	public void reset()
	{
		combatSeen = false;
		lastCombatTick = 0;
	}
/** Marks combat when the local player starts targeting an actor, or another actor starts targeting the player. */
	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (client == null || event == null)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		Actor source = event.getSource();
		Actor target = event.getTarget();
		if ((source == local && target != null) || (target == local && source != null && source != local))
		{
			markCombat();
		}
	}
/** Marks combat on any hitsplat dealt by the local player or applied to them. */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (client == null || event == null || event.getHitsplat() == null)
		{
			return;
		}
		if (event.getActor() == client.getLocalPlayer() || event.getHitsplat().isMine())
		{
			markCombat();
		}
	}
/** Clears combat state on logout or world hop so stale ticks from a previous session don't leak. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			reset();
		}
	}
/**
	 * Whether {@code currentTick} falls within {@code lockoutTicks} of {@code lastCombatTick}. A negative
	 * elapsed time (tick counter reset) is treated as out of combat.
	 */
	static boolean withinLockout(boolean combatSeen, int lastCombatTick, int currentTick, int lockoutTicks)
	{
		if (!combatSeen)
		{
			return false;
		}
		long elapsed = (long) currentTick - lastCombatTick;
		return elapsed >= 0L && elapsed <= Math.max(0, lockoutTicks);
	}

	private void markCombat()
	{
		combatSeen = true;
		lastCombatTick = client.getTickCount();
	}
}

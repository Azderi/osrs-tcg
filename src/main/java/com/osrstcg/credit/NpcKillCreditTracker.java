package com.osrstcg.credit;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrstcg.ui.SidebarRefresh;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Awards credits for player-caused NPC kills. Tracks which NPCs the player has recently interacted with or
 * hit so a death is only credited when it was actually caused by the player within a short timeout.
 * Subscribes to {@link InteractingChanged}, {@link HitsplatApplied}, {@link ActorDeath}, and {@link GameTick}.
 */
@Singleton
public final class NpcKillCreditTracker
{
	/** Ticks after the last player interaction/hit on an NPC before it's no longer considered engaged. */
	private static final int INTERACT_TIMEOUT_TICKS = 12;

	private final Client client;
	private final ClientThread clientThread;
	private final CreditAwardService creditAwardService;
	private final CreditAttestQueue attestQueue;
	private final ActivityConfigService activityConfigService;
	private final SidebarRefresh sidebarRefresh;

	/** Last known display name per NPC index. */
	private final Map<Integer, String> lastKnownNpcName = new ConcurrentHashMap<>();
	/** Tick of the last player interaction/hit per NPC index, for the engagement timeout. */
	private final Map<Integer, Integer> lastInteractionTicks = new ConcurrentHashMap<>();
	/** Whether the player has interacted with or hit the NPC at this index recently enough to credit its death. */
	private final Map<Integer, Boolean> wasNpcEngaged = new ConcurrentHashMap<>();

	@Inject
	public NpcKillCreditTracker(
		Client client,
		ClientThread clientThread,
		CreditAwardService creditAwardService,
		CreditAttestQueue attestQueue,
		ActivityConfigService activityConfigService,
		SidebarRefresh sidebarRefresh)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.creditAwardService = creditAwardService;
		this.attestQueue = attestQueue;
		this.activityConfigService = activityConfigService;
		this.sidebarRefresh = sidebarRefresh;
	}

	/** Clears all tracked NPC interaction state. */
	public void shutdown()
	{
		lastKnownNpcName.clear();
		lastInteractionTicks.clear();
		wasNpcEngaged.clear();
	}

	/** Marks an NPC the player has just targeted as engaged, so a fast/one-hit kill still qualifies. */
	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!creditAwardService.isCreditTrackingAllowed())
		{
			return;
		}

		Actor source = event.getSource();
		Actor target = event.getTarget();

		if (source == client.getLocalPlayer() && target instanceof NPC)
		{
			NPC npc = (NPC) target;
			int npcIndex = npc.getIndex();
			String npcName = Optional.ofNullable(npc.getName()).orElse("Unnamed NPC");

			lastKnownNpcName.put(npcIndex, npcName);
			lastInteractionTicks.put(npcIndex, client.getTickCount());
			// Count targeting as engagement so one-hit kills still qualify if ActorDeath runs before HitsplatApplied.
			wasNpcEngaged.put(npcIndex, true);
		}
	}

	/** Marks an NPC hit by the player's own hitsplat as engaged and refreshes its interaction timeout. */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!creditAwardService.isCreditTrackingAllowed())
		{
			return;
		}

		Actor target = event.getActor();
		Hitsplat hitsplat = event.getHitsplat();

		if (target instanceof NPC && hitsplat.isMine())
		{
			NPC npc = (NPC) target;
			int npcIndex = npc.getIndex();
			String npcName = Optional.ofNullable(npc.getName()).orElse(lastKnownNpcName.getOrDefault(npcIndex, "Unnamed NPC"));

			lastKnownNpcName.put(npcIndex, npcName);
			lastInteractionTicks.put(npcIndex, client.getTickCount());
			wasNpcEngaged.put(npcIndex, true);
		}
	}

	/**
	 * On an NPC death, awards npc-kill credit if the player was recently engaged with it. Deferred to the
	 * client thread since engagement state can be checked/cleared from event handlers on the same tick.
	 */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!creditAwardService.isCreditTrackingAllowed())
		{
			return;
		}

		Actor actor = event.getActor();

		if (!(actor instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) actor;
		int npcIndex = npc.getIndex();
		int npcId = npc.getId();
		String npcName = normalizeName(lastKnownNpcName.getOrDefault(npcIndex, npc.getName()));

		if (activityConfigService.getCompiled().isExcludedNpc(npcId))
		{
			cleanupAfterLogging(npcIndex);
			return;
		}

		final int idx = npcIndex;
		final String awardName = npcName;
		final int combatLevel = npc.getCombatLevel();
		final int awardNpcId = npcId;
		clientThread.invokeLater(() ->
		{
			try
			{
				if (Boolean.TRUE.equals(wasNpcEngaged.get(idx)) && isInteractionValid(idx))
				{
					enqueueNpcKillCredit(awardName, combatLevel, awardNpcId);
					sidebarRefresh.refreshCredits();
				}
			}
			finally
			{
				cleanupAfterLogging(idx);
			}
		});
	}

	/** Expires stale interaction timestamps once past {@link #INTERACT_TIMEOUT_TICKS}. */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		int currentTick = client.getTickCount();
		lastInteractionTicks.keySet().removeIf(npcIndex ->
			(currentTick - lastInteractionTicks.get(npcIndex)) > INTERACT_TIMEOUT_TICKS);
	}

	/**
	 * Enqueues an {@code npc_kill} attest event; optimistic credits are
	 * {@code round(combatLevel * killCreditMultiplier)}, defaulting to 1× when unset.
	 */
	private void enqueueNpcKillCredit(String npcName, int combatLevel, int npcId)
	{
		if (combatLevel <= 0 || creditAwardService.isCreditAwardOnCooldown()
			|| !creditAwardService.isCreditTrackingAllowed())
		{
			return;
		}
		double multiplier = activityConfigService.getCompiled().getKillCreditMultiplier(npcId);
		long optimisticCredits = Math.round(combatLevel * multiplier);
		if (optimisticCredits <= 0L)
		{
			return;
		}
		JsonObject evidence = new JsonObject();
		evidence.addProperty("combatLevel", combatLevel);
		if (npcId > 0)
		{
			evidence.addProperty("npcId", npcId);
		}
		if (npcName != null && !npcName.isEmpty())
		{
			evidence.addProperty("npcName", npcName);
		}
		attestQueue.enqueue("npc_kill", evidence, optimisticCredits);
	}

	/** Strips RuneLite formatting tags from an NPC name, defaulting to "Unnamed NPC" for {@code null}. */
	private static String normalizeName(String npcName)
	{
		if (npcName == null)
		{
			return "Unnamed NPC";
		}
		return npcName.replaceAll("<.*?>", "").trim();
	}

	/** Whether the NPC at {@code npcIndex} was interacted with/hit within {@link #INTERACT_TIMEOUT_TICKS}. */
	private boolean isInteractionValid(int npcIndex)
	{
		Integer lastTick = lastInteractionTicks.get(npcIndex);
		return lastTick != null && (client.getTickCount() - lastTick) <= INTERACT_TIMEOUT_TICKS;
	}

	/** Removes tracked interaction state for an NPC index after its death has been handled. */
	private void cleanupAfterLogging(int npcIndex)
	{
		lastKnownNpcName.remove(npcIndex);
		lastInteractionTicks.remove(npcIndex);
		wasNpcEngaged.remove(npcIndex);
	}
}

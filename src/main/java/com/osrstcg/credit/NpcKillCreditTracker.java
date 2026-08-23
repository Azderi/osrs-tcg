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
 * Awards kill credits from actual NPC deaths (including zero-loot kills), using the same engagement
 * signals as {@code monster-monitor}: player target + player damage within a short tick window.
 * Replaces credits tied to {@link net.runelite.client.plugins.loottracker.LootReceived}.
 * <p>
 * NPC kill exclusions come from server activity config ({@link ActivityConfigService}).
 */
@Singleton
public final class NpcKillCreditTracker
{
	private static final int INTERACTION_TIMEOUT_TICKS = 12;

	private final Client client;
	private final ClientThread clientThread;
	private final CreditAwardService creditAwardService;
	private final CreditAttestQueue attestQueue;
	private final ActivityConfigService activityConfigService;
	private final SidebarRefresh sidebarRefresh;

	private final Map<Integer, String> lastKnownNpcName = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> lastInteractionTicks = new ConcurrentHashMap<>();
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

	public void shutdown()
	{
		lastKnownNpcName.clear();
		lastInteractionTicks.clear();
		wasNpcEngaged.clear();
	}

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

		if (isExcludedNpc(npcId))
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
					enqueueNpcKillCredit(creditAwardService, attestQueue, awardName, combatLevel, awardNpcId);
					sidebarRefresh.refreshCredits();
				}
			}
			finally
			{
				cleanupAfterLogging(idx);
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int currentTick = client.getTickCount();
		lastInteractionTicks.keySet().removeIf(npcIndex ->
			(currentTick - lastInteractionTicks.get(npcIndex)) > INTERACTION_TIMEOUT_TICKS);
	}

	/** Enqueues an {@code npc_kill} attest event; optimistic credits equal combat level (1×). */
	private static void enqueueNpcKillCredit(
		CreditAwardService creditAwardService,
		CreditAttestQueue attestQueue,
		String npcName,
		int combatLevel,
		int npcId)
	{
		if (combatLevel <= 0 || creditAwardService.isCreditAwardOnCooldown()
			|| !creditAwardService.isCreditTrackingAllowed())
		{
			return;
		}
		long optimisticCredits = combatLevel;
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

	private static String normalizeName(String npcName)
	{
		if (npcName == null)
		{
			return "Unnamed NPC";
		}
		return npcName.replaceAll("<.*?>", "").trim();
	}

	private boolean isExcludedNpc(int npcId)
	{
		return activityConfigService.getCompiled().isExcludedNpc(npcId);
	}

	private boolean isInteractionValid(int npcIndex)
	{
		Integer lastTick = lastInteractionTicks.get(npcIndex);
		return lastTick != null && (client.getTickCount() - lastTick) <= INTERACTION_TIMEOUT_TICKS;
	}

	private void cleanupAfterLogging(int npcIndex)
	{
		lastKnownNpcName.remove(npcIndex);
		lastInteractionTicks.remove(npcIndex);
		wasNpcEngaged.remove(npcIndex);
	}
}

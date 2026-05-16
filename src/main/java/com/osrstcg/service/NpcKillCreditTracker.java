package com.runelitetcg.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.runelitetcg.ui.TcgPanel;
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
 */
@Singleton
public final class NpcKillCreditTracker
{
	private static final int INTERACTION_TIMEOUT_TICKS = 7;

	/** Boss display name -> NPC ids that count as the real kill (final phase only). */
	private static final Map<String, Set<Integer>> FINAL_PHASE_IDS = Map.ofEntries(
		Map.entry("Kalphite Queen", Set.of(965)),
		Map.entry("The Nightmare", Set.of(378)),
		Map.entry("Phosani's Nightmare", Set.of(377)),
		Map.entry("Alchemical Hydra", Set.of(8622)),
		Map.entry("Hydra", Set.of(8609)),
		Map.entry("Phantom Muspah", Set.of(12082)),
		Map.entry("Dusk", Set.of(7889)),
		Map.entry("Abyssal Sire", Set.of(5891)),
		Map.entry("Kephri", Set.of(11722)),
		Map.entry("Verzik Vitur", Set.of(10832, 8371, 10849)),
		Map.entry("Great Olm", Set.of(7551))
	);

	/** Minions / non-kill phases — no credit (matches monster-monitor exclusions). */
	private static final Map<String, Set<Integer>> EXCLUDED_NPC_IDS = Map.ofEntries(
		Map.entry("The Hueycoatl", Set.of(14010, 14011, 14013)),
		Map.entry("Hueycoatl Tail", Set.of(14014, 14015)),
		Map.entry("Hueycoatl Tail (Broken)", Set.of(14014, 14015)),
		Map.entry("Hueycoatl body", Set.of(14017, 14018)),
		Map.entry("Dawn", Set.of(7888)),
		Map.entry("Unstable ice", Set.of(13688)),
		Map.entry("Cracked Ice", Set.of(13026)),
		Map.entry("Rubble", Set.of(14018)),
		Map.entry("Great Olm Right Claw", Set.of(7550, 7553)),
		Map.entry("Great Olm Left Claw", Set.of(7552, 7555))
	);

	private final Client client;
	private final ClientThread clientThread;
	private final CreditAwardService creditAwardService;
	private final TcgPanel tcgPanel;
	private final SpecialNpcCreditWatch specialNpcCreditWatch;

	private final Map<Integer, String> lastKnownNpcName = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> lastInteractionTicks = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> wasNpcEngaged = new ConcurrentHashMap<>();

	@Inject
	public NpcKillCreditTracker(
		Client client,
		ClientThread clientThread,
		CreditAwardService creditAwardService,
		TcgPanel tcgPanel)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.creditAwardService = creditAwardService;
		this.tcgPanel = tcgPanel;
		this.specialNpcCreditWatch = new SpecialNpcCreditWatch(clientThread, creditAwardService, tcgPanel);
	}

	public void shutdown()
	{
		specialNpcCreditWatch.shutdown();
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
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

			if (specialNpcCreditWatch.isSpecialNpc(npc))
			{
				specialNpcCreditWatch.trackNpc(npc);
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
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
			specialNpcCreditWatch.retrackAfterPlayerHit(npc);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();

		if (!(actor instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) actor;
		int npcIndex = npc.getIndex();
		int npcId = npc.getId();
		String npcName = normalizeName(lastKnownNpcName.getOrDefault(npcIndex, npc.getName()));

		if (isExcludedNpc(npcName, npcId))
		{
			cleanupAfterLogging(npcIndex);
			return;
		}

		if (specialNpcCreditWatch.isSpecialNpc(npc))
		{
			cleanupAfterLogging(npcIndex);
			return;
		}

		if (FINAL_PHASE_IDS.containsKey(npcName))
		{
			Set<Integer> finalIds = FINAL_PHASE_IDS.get(npcName);
			if (!finalIds.contains(npcId))
			{
				cleanupAfterLogging(npcIndex);
				return;
			}
		}

		final int idx = npcIndex;
		final String awardName = npcName;
		final int combatLevel = npc.getCombatLevel();
		clientThread.invokeLater(() ->
		{
			try
			{
				if (Boolean.TRUE.equals(wasNpcEngaged.get(idx)) && isInteractionValid(idx))
				{
					creditAwardService.awardNpcKillCredits(awardName, combatLevel);
					tcgPanel.refresh();
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
		specialNpcCreditWatch.updateTrackedNpcs();

		lastInteractionTicks.keySet().removeIf(npcIndex ->
			(currentTick - lastInteractionTicks.get(npcIndex)) > INTERACTION_TIMEOUT_TICKS);
	}

	private static String normalizeName(String npcName)
	{
		if (npcName == null)
		{
			return "Unnamed NPC";
		}
		return npcName.replaceAll("<.*?>", "").trim();
	}

	private boolean isExcludedNpc(String npcName, int npcId)
	{
		if (EXCLUDED_NPC_IDS.containsKey(npcName) && EXCLUDED_NPC_IDS.get(npcName).contains(npcId))
		{
			return true;
		}
		for (Set<Integer> excludedIds : EXCLUDED_NPC_IDS.values())
		{
			if (excludedIds.contains(npcId))
			{
				return true;
			}
		}
		return false;
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

	/**
	 * NPCs that may not fire {@link ActorDeath}; death inferred from health ratio (see monster-monitor).
	 */
	private static final class SpecialNpcCreditWatch
	{
		private final Map<Integer, NPC> trackedNpcs = new ConcurrentHashMap<>();
		private final Set<Integer> specialNpcIds = Set.of(
			14009, 14012, 13685, 12166, 13013, 13012, 13011
		);
		private final Set<Integer> loggedNpcIndices = ConcurrentHashMap.newKeySet();
		private final ClientThread clientThread;
		private final CreditAwardService creditAwardService;
		private final TcgPanel tcgPanel;

		private SpecialNpcCreditWatch(ClientThread clientThread, CreditAwardService creditAwardService, TcgPanel tcgPanel)
		{
			this.clientThread = clientThread;
			this.creditAwardService = creditAwardService;
			this.tcgPanel = tcgPanel;
		}

		boolean isSpecialNpc(NPC npc)
		{
			return npc != null && specialNpcIds.contains(npc.getId());
		}

		void trackNpc(NPC npc)
		{
			if (isSpecialNpc(npc))
			{
				trackedNpcs.put(npc.getIndex(), npc);
			}
		}

		void updateTrackedNpcs()
		{
			clientThread.invokeLater(() ->
			{
				for (NPC npc : Set.copyOf(trackedNpcs.values()))
				{
					if (npc == null)
					{
						continue;
					}
					int healthRatio = npc.getHealthRatio();
					int npcIndex = npc.getIndex();

					if (healthRatio <= 1 && !loggedNpcIndices.contains(npcIndex) && shouldCredit(npc))
					{
						loggedNpcIndices.add(npcIndex);
						String name = normalizeName(npc.getName());
						int combatLevel = npc.getCombatLevel();
						creditAwardService.awardNpcKillCredits(name, combatLevel);
						tcgPanel.refresh();
					}
					else if (healthRatio > 0 && loggedNpcIndices.contains(npcIndex))
					{
						loggedNpcIndices.remove(npcIndex);
						trackNpc(npc);
					}
				}
			});
		}

		private static boolean shouldCredit(NPC npc)
		{
			if ("The Hueycoatl".equalsIgnoreCase(npc.getName()))
			{
				return npc.getId() == 14012;
			}
			return true;
		}

		private static String normalizeName(String npcName)
		{
			if (npcName == null)
			{
				return "Unnamed NPC";
			}
			return npcName.replaceAll("<.*?>", "").trim();
		}

		void retrackAfterPlayerHit(NPC npc)
		{
			if (npc == null)
			{
				return;
			}
			int npcIndex = npc.getIndex();
			if (loggedNpcIndices.contains(npcIndex))
			{
				loggedNpcIndices.remove(npcIndex);
				trackNpc(npc);
			}
		}

		void shutdown()
		{
			trackedNpcs.clear();
			loggedNpcIndices.clear();
		}
	}
}

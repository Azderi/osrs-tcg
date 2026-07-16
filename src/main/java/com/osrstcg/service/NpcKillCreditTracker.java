package com.osrstcg.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrstcg.ui.TcgPanel;
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
		Map.entry("Kephri", Set.of(11722)),
		Map.entry("Verzik Vitur", Set.of(10832, 8371, 10849))
	);

	/** Kill-credit exclusions: exact name, name fragment (with optional exception), or NPC id. */
	private static final NpcExclusionRule[] NPC_EXCLUSIONS = {
		NpcExclusionRule.exactName("Alchemical Hydra"),
		NpcExclusionRule.exactName("The Hueycoatl"),
		NpcExclusionRule.nameContains("nylocas", "Nylocas Vasilias"),
		NpcExclusionRule.npcIds(ExcludedNpcIds.HUEYCOATL),
		NpcExclusionRule.npcIds(ExcludedNpcIds.GROTESQUE_GUARDIANS),
		NpcExclusionRule.npcIds(ExcludedNpcIds.ROYAL_TITANS),
		NpcExclusionRule.npcIds(ExcludedNpcIds.ALCHEMICAL_HYDRA_PHASES),
		NpcExclusionRule.npcIds(ExcludedNpcIds.AMOXLIATL_UNSTABLE_ICE),
		NpcExclusionRule.npcIds(ExcludedNpcIds.CRACKED_ICE),
		NpcExclusionRule.npcIds(ExcludedNpcIds.GREAT_OLM),
		NpcExclusionRule.exactName("The Nightmare"),
		NpcExclusionRule.exactName("Phosani's Nightmare"),
		NpcExclusionRule.npcIds(ExcludedNpcIds.THE_NIGHTMARE),
		NpcExclusionRule.npcIds(ExcludedNpcIds.PHANTOM_MUSPAH),
		NpcExclusionRule.exactName("Abyssal Sire"),
		NpcExclusionRule.npcIds(ExcludedNpcIds.ABYSSAL_SIRE),
		NpcExclusionRule.npcIds(ExcludedNpcIds.THE_GAUNTLET),
		NpcExclusionRule.npcIds(ExcludedNpcIds.SHELLBANE_GRYPHON),
	};

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
		String normalized = normalizeName(npcName);
		for (NpcExclusionRule rule : NPC_EXCLUSIONS)
		{
			if (rule.matches(normalized, npcId))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isExcludedNpcName(String npcName)
	{
		String normalized = normalizeName(npcName);
		for (NpcExclusionRule rule : NPC_EXCLUSIONS)
		{
			if (rule.matchesName(normalized))
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
	 * NPC ids for minions / non-kill phases (matches monster-monitor exclusions).
	 * Real boss kills are tracked via {@link #FINAL_PHASE_IDS}, {@link HealthTrackedNpcIds}, or
	 * {@link GameMessageCreditTracker}.
	 */
	private static final class ExcludedNpcIds
	{
		/** The Hueycoatl — all forms (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> HUEYCOATL = Set.of(
			14009, 14010, 14011, 14012, 14013, 14014, 14015, 14017);

		/** Grotesque Guardians — Dawn and Dusk (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> GROTESQUE_GUARDIANS = Set.of(
			7851, 7852, 7853, 7854, 7855, 7882, 7883, 7884, 7885, 7886, 7887, 7888, 7889);

		/** Royal Titans — all forms (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> ROYAL_TITANS = Set.of(
			12596, 14147, 14148, 14149, 14150, 14151, 14152, 14153);

		/** Alchemical Hydra — (kill credits via {@link GameMessageCreditTracker}).*/
		static final Set<Integer> ALCHEMICAL_HYDRA_PHASES = Set.of(8615, 8619, 8620, 8621);

		/** Amoxliatl — unstable ice blocks during the fight. */
		static final Set<Integer> AMOXLIATL_UNSTABLE_ICE = Set.of(13688);

		/** Cracked Ice — Blue Moon. */
		static final Set<Integer> CRACKED_ICE = Set.of(13026);

		/** Great Olm — head and claws, normal and challenge mode (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> GREAT_OLM = Set.of(7550, 7551, 7552, 7553, 7554, 7555);

		/** The Nightmare — minions and non-kill phases (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> THE_NIGHTMARE = Set.of(
			9417, 9418, 9420, 9421, 9422, 9424, 9435, 9438, 9441, 9444, 9445, 9446,
			9466, 9467, 9469, 9470, 11153, 11154, 11155);

		/** Phantom Muspah — all forms (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> PHANTOM_MUSPAH = Set.of(
			12077, 12078, 12079, 12080, 12082, 15549);

		/** Abyssal Sire — all phases (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> ABYSSAL_SIRE = Set.of(
			5886, 5887, 5888, 5889, 5890, 5891, 5908);

		/** The Gauntlet — crystalline/corrupted creatures and tornadoes (credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> THE_GAUNTLET = Set.of(
			9021, 9022, 9023, 9024, 9025, 9026, 9027, 9028, 9029, 9030, 9031, 9032, 9033, 9034,
			9035, 9036, 9037, 9038, 9039, 9040, 9041, 9042, 9043, 9044, 9046, 9047, 9048);

		/** Shellbane gryphon — all forms (kill credits via {@link GameMessageCreditTracker}). */
		static final Set<Integer> SHELLBANE_GRYPHON = Set.of(14860, 15010);

		private ExcludedNpcIds()
		{
		}
	}

	/**
	 * Boss NPC ids tracked via health ratio when {@link ActorDeath} does not fire (see monster-monitor).
	 */
	private static final class HealthTrackedNpcIds
	{
		/** Amoxliatl — Varlamore frost boss. */
		static final int AMOXLIATL = 13685;

		/** Duke Sucellus — DT2 boss. */
		static final int DUKE_SUCELLUS = 12166;

		/** Perilous Moons — Blood Moon. */
		static final int BLOOD_MOON = 13011;

		/** Perilous Moons — Eclipse Moon. */
		static final int ECLIPSE_MOON = 13012;

		/** Perilous Moons — Blue Moon. */
		static final int BLUE_MOON = 13013;

		static final Set<Integer> ALL = Set.of(
			AMOXLIATL,
			DUKE_SUCELLUS,
			BLOOD_MOON,
			ECLIPSE_MOON,
			BLUE_MOON);

		private HealthTrackedNpcIds()
		{
		}
	}

	private static final class NpcExclusionRule
	{
		private enum MatchMode
		{
			EXACT_NAME,
			NAME_CONTAINS,
			NPC_ID
		}

		private final MatchMode mode;
		private final String pattern;
		private final String exceptName;
		private final Set<Integer> npcIds;

		private NpcExclusionRule(MatchMode mode, String pattern, String exceptName, Set<Integer> npcIds)
		{
			this.mode = mode;
			this.pattern = pattern;
			this.exceptName = exceptName;
			this.npcIds = npcIds;
		}

		static NpcExclusionRule exactName(String name)
		{
			return new NpcExclusionRule(MatchMode.EXACT_NAME, name, null, Set.of());
		}

		static NpcExclusionRule nameContains(String fragment, String exceptName)
		{
			return new NpcExclusionRule(MatchMode.NAME_CONTAINS, fragment, exceptName, Set.of());
		}

		static NpcExclusionRule npcIds(Set<Integer> ids)
		{
			return new NpcExclusionRule(MatchMode.NPC_ID, null, null, ids);
		}

		boolean matches(String normalizedName, int npcId)
		{
			switch (mode)
			{
				case EXACT_NAME:
					return matchesExactName(normalizedName);
				case NAME_CONTAINS:
					return matchesNameContains(normalizedName);
				case NPC_ID:
					return npcIds.contains(npcId);
				default:
					return false;
			}
		}

		boolean matchesName(String normalizedName)
		{
			switch (mode)
			{
				case EXACT_NAME:
					return matchesExactName(normalizedName);
				case NAME_CONTAINS:
					return matchesNameContains(normalizedName);
				default:
					return false;
			}
		}

		private boolean matchesExactName(String normalizedName)
		{
			return pattern.equalsIgnoreCase(normalizedName);
		}

		private boolean matchesNameContains(String normalizedName)
		{
			return normalizedName.toLowerCase(Locale.ENGLISH).contains(pattern.toLowerCase(Locale.ENGLISH))
				&& !exceptName.equalsIgnoreCase(normalizedName);
		}
	}

	/**
	 * NPCs that may not fire {@link ActorDeath}; death inferred from health ratio (see monster-monitor).
	 */
	private static final class SpecialNpcCreditWatch
	{
		private final Map<Integer, NPC> trackedNpcs = new ConcurrentHashMap<>();
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
			return npc != null && HealthTrackedNpcIds.ALL.contains(npc.getId());
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

					if (healthRatio <= 1 && !loggedNpcIndices.contains(npcIndex))
					{
						loggedNpcIndices.add(npcIndex);
						String name = normalizeName(npc.getName());
						if (isExcludedNpcName(name))
						{
							continue;
						}
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

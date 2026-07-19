package com.osrstcg.service;

import com.osrstcg.model.SkillCreditBaseline;
import com.osrstcg.util.NumberFormatting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

@Singleton
@Slf4j
public class CreditAwardService
{
	private static final long XP_PER_CREDIT_CHUNK = 1000L;
	private static final long CREDITS_PER_CHUNK = 100L;
	/** Level-up bonus at levels 1–2; base of the exponential curve to level 99. */
	private static final int LEVEL_UP_REWARD_FLOOR = 1_250;
	/** Level-up bonus at level 99 and each virtual level after (100–126). */
	private static final int LEVEL_UP_REWARD_CAP = 25_000;
	private static final int LEVEL_UP_PROGRESS_LEVELS = 97;
	private static final double LEVEL_UP_CURVE_STEEPNESS = 2.5d;
	/** Ignore bogus fake XP drop payloads. */
	private static final int FAKE_XP_DROP_SANITY_CAP = 20_000_000;
	/** Suppress credit awards while stats settle after login or a world hop. */
	private static final int CREDIT_AWARD_COOLDOWN_TICKS = 3;
	private static final Set<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK,
		Skill.DEFENCE,
		Skill.STRENGTH,
		Skill.HITPOINTS,
		Skill.MAGIC,
		Skill.RANGED
	);

	private final Client client;
	private final TcgStateService stateService;
	/** Last known skill level including virtual levels past 99 (1–126). */
	private final Map<Skill, Integer> lastKnownLevels = new EnumMap<>(Skill.class);
	private final int[] previousSkillXp = new int[Skill.values().length];
	private boolean skillLevelsInitialized;
	private boolean skillXpInitialized;
	private boolean creditAwardCooldownActive;
	private int creditAwardCooldownUntilTick;
	private boolean pendingStatsSettleAfterLoginOrHop;
	/** When true, restore uncredited XP from the persisted snapshot after settle (login / logout). */
	private boolean restoreUncreditedXpFromPersistedBaseline;
	private GameState lastObservedGameState;

	/** XP from skill drops not yet converted into credit chunks. */
	private long uncreditedXp;

	@Inject
	public CreditAwardService(Client client, TcgStateService stateService)
	{
		this.client = client;
		this.stateService = stateService;
	}

	/**
	 * Call when the RuneScape profile (or persisted plugin state) changes so we do not compare XP across characters.
	 * Keeps the persisted skill snapshot for post-settle retroactive awards; only resets live tracking.
	 */
	public void resetExperienceCreditBaseline()
	{
		skillXpInitialized = false;
		skillLevelsInitialized = false;
		lastKnownLevels.clear();
		Arrays.fill(previousSkillXp, 0);

		SkillCreditBaseline saved = stateService.getState().getSkillCreditBaseline();
		if (saved != null && saved.isPresent())
		{
			uncreditedXp = saved.getUncreditedXp();
		}
		else
		{
			clearUncreditedXpPool("profile change");
		}
		// Do not snapshot live client stats here — wait for the settle cooldown so retro awards can run first.
	}

	public void awardNpcKillCredits(String npcName, int combatLevel)
	{
		if (combatLevel <= 0 || isCreditAwardOnCooldown())
		{
			return;
		}

		int creditsPerKill = applyKillCreditTuning(combatLevel);
		long totalCredits = creditsPerKill;
		if (totalCredits <= 0)
		{
			return;
		}

		addCredits(totalCredits);
		debugAward(String.format("Killed %s (lvl %d) -> +%s credits (total %s)",
			safeName(npcName), combatLevel, NumberFormatting.format(totalCredits), NumberFormatting.format(stateService.getCredits())));
	}

	public void awardFlatCredits(String reason, long credits)
	{
		if (credits <= 0L || isCreditAwardOnCooldown())
		{
			return;
		}

		addCredits(credits);
		debugAward(String.format("%s -> +%s credits (total %s)",
			safeName(reason), NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
	}

	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}

		int currentXp = event.getXp();
		trackXpGainFromStatChanged(skill, currentXp);

		if (isCreditAwardOnCooldown())
		{
			return;
		}

		if (isOverallSkill(skill))
		{
			return;
		}

		int current = levelForXp(currentXp);
		if (!skillLevelsInitialized || !lastKnownLevels.containsKey(skill))
		{
			lastKnownLevels.put(skill, current);
			return;
		}

		int previous = lastKnownLevels.get(skill);

		// Skill levels cannot decrease; ignore transient drops (e.g. disconnect/reconnect).
		if (current <= previous)
		{
			return;
		}

		awardLevelUps(skill, previous, current);
		lastKnownLevels.put(skill, current);
	}

	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (event == null || event.getSkill() == null || isCreditAwardOnCooldown())
		{
			return;
		}

		Skill skill = event.getSkill();
		if (isCombatSkill(skill))
		{
			int xp = event.getXp();
			if (xp > 0 && xp < FAKE_XP_DROP_SANITY_CAP)
			{
				debugAward(String.format(
					"Ignored fake XP drop for combat skill %s (+%s XP)",
					skill.getName(), NumberFormatting.format(xp)));
			}
			return;
		}

		if (!isGenuineMaxedSkillFakeXpDrop(skill))
		{
			debugAward(String.format(
				"Ignored fake XP drop for %s (skill below %s XP)",
				skill.getName(), NumberFormatting.format(Experience.MAX_SKILL_XP)));
			return;
		}

		int xp = event.getXp();
		if (xp <= 0 || xp >= FAKE_XP_DROP_SANITY_CAP)
		{
			return;
		}

		applyXpGain(xp, skill.getName() + " drop");
	}

	/** Call when the plugin is enabled mid-session so stats are not credited against empty baselines. */
	public void onPluginStarted()
	{
		if (client == null)
		{
			return;
		}

		lastObservedGameState = client.getGameState();
		GameState current = lastObservedGameState;
		if (current == GameState.LOGIN_SCREEN)
		{
			pendingStatsSettleAfterLoginOrHop = true;
			restoreUncreditedXpFromPersistedBaseline = true;
			suppressCreditAwardsUntilStatsSettle(true);
		}
		else if (current == GameState.HOPPING)
		{
			pendingStatsSettleAfterLoginOrHop = true;
			restoreUncreditedXpFromPersistedBaseline = false;
			suppressCreditAwardsUntilStatsSettle(false);
		}
		else if (current == GameState.LOGGED_IN)
		{
			pendingStatsSettleAfterLoginOrHop = true;
			restoreUncreditedXpFromPersistedBaseline = false;
			suppressCreditAwardsUntilStatsSettle(false);
		}
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		GameState next = event.getGameState();
		lastObservedGameState = next;

		if (next == GameState.LOGIN_SCREEN)
		{
			persistSkillBaselineToState(true);
			pendingStatsSettleAfterLoginOrHop = true;
			restoreUncreditedXpFromPersistedBaseline = true;
			suppressCreditAwardsUntilStatsSettle(true);
			return;
		}

		if (next == GameState.HOPPING)
		{
			persistSkillBaselineToState(true);
			pendingStatsSettleAfterLoginOrHop = true;
			restoreUncreditedXpFromPersistedBaseline = false;
			suppressCreditAwardsUntilStatsSettle(false);
			return;
		}

		if (next != GameState.LOGGED_IN)
		{
			return;
		}

		if (pendingStatsSettleAfterLoginOrHop)
		{
			// Tracking was already reset at login screen / hop; realign to post-login ticks only.
			beginCreditAwardCooldown();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (creditAwardCooldownActive)
		{
			if (isCreditAwardOnCooldown())
			{
				return;
			}

			creditAwardCooldownActive = false;
			pendingStatsSettleAfterLoginOrHop = false;
			applyRetroactiveCreditsFromPersistedBaseline();
			if (!skillXpInitialized || !skillLevelsInitialized)
			{
				snapshotSkillBaselinesIfLoggedIn();
			}
			persistSkillBaselineToState(true);
			debugAward("Credit award cooldown ended; resuming credit gains");
			return;
		}

		if (!skillXpInitialized || !skillLevelsInitialized)
		{
			snapshotSkillBaselinesIfLoggedIn();
			persistSkillBaselineToState(false);
		}
	}

	private void applyRetroactiveCreditsFromPersistedBaseline()
	{
		SkillCreditBaseline saved = stateService.getState().getSkillCreditBaseline();
		if (saved == null || !saved.isPresent())
		{
			restoreUncreditedXpFromPersistedBaseline = false;
			return;
		}

		if (restoreUncreditedXpFromPersistedBaseline)
		{
			uncreditedXp = saved.getUncreditedXp();
			restoreUncreditedXpFromPersistedBaseline = false;
		}

		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int[] liveXp = client.getSkillExperiences();
		if (liveXp == null)
		{
			return;
		}

		long pendingNonCombatXp = 0L;
		List<RetroLevelUp> pendingLevelUps = new ArrayList<>();

		for (Skill skill : Skill.values())
		{
			if (isOverallSkill(skill))
			{
				continue;
			}

			OptionalInt savedXpOpt = saved.xpFor(skill);
			if (savedXpOpt.isEmpty())
			{
				continue;
			}

			int skillIndex = skill.ordinal();
			if (skillIndex < 0 || skillIndex >= liveXp.length)
			{
				continue;
			}

			int previousXp = savedXpOpt.getAsInt();
			int currentXp = Math.max(0, liveXp[skillIndex]);
			if (currentXp < previousXp)
			{
				continue;
			}

			long gained = (long) currentXp - previousXp;
			if (gained > 0L)
			{
				if (isCombatSkill(skill))
				{
					debugAward(String.format(
						"Retro: ignored +%s combat skill XP (%s)",
						NumberFormatting.format(gained), skill.getName()));
				}
				else
				{
					pendingNonCombatXp += gained;
					debugAward(String.format(
						"Retro: +%s XP (%s) since last snapshot",
						NumberFormatting.format(gained), skill.getName()));
				}
			}

			int previousLevel = levelForXp(previousXp);
			int currentLevel = levelForXp(currentXp);
			if (currentLevel > previousLevel)
			{
				pendingLevelUps.add(new RetroLevelUp(skill, previousLevel, currentLevel));
			}
		}

		// Persist live skill XP before awarding so a crash cannot double-pay the same gap next login.
		snapshotSkillBaselinesIfLoggedIn();
		persistSkillBaselineToState(true);

		long levelCredits = 0L;
		for (RetroLevelUp levelUp : pendingLevelUps)
		{
			levelCredits += awardLevelUps(levelUp.skill, levelUp.previousLevel, levelUp.currentLevel);
		}
		if (pendingNonCombatXp > 0L)
		{
			applyXpGain(pendingNonCombatXp, "offline");
		}

		if (pendingNonCombatXp > 0L || levelCredits > 0L)
		{
			debugAward(String.format(
				"Retroactive skill credits after settle: +%s non-combat XP, +%s level-up credits (total %s)",
				NumberFormatting.format(pendingNonCombatXp),
				NumberFormatting.format(levelCredits),
				NumberFormatting.format(stateService.getCredits())));
		}
		else
		{
			debugAward("Retroactive skill check after settle: no XP or level gains since last snapshot");
		}
	}

	private static final class RetroLevelUp
	{
		private final Skill skill;
		private final int previousLevel;
		private final int currentLevel;

		private RetroLevelUp(Skill skill, int previousLevel, int currentLevel)
		{
			this.skill = skill;
			this.previousLevel = previousLevel;
			this.currentLevel = currentLevel;
		}
	}

	private long awardLevelUps(Skill skill, int previousLevel, int currentLevel)
	{
		long totalReward = awardLevelUps(previousLevel, currentLevel);
		if (totalReward > 0L && skill != null)
		{
			debugAward(String.format("Level up %s: %d -> %d -> +%s credits (total %s)",
				skill.getName(), previousLevel, currentLevel,
				NumberFormatting.format(totalReward), NumberFormatting.format(stateService.getCredits())));
		}
		return totalReward;
	}

	private long awardLevelUps(int previousLevel, int currentLevel)
	{
		if (currentLevel <= previousLevel)
		{
			return 0L;
		}

		long totalReward = 0L;
		double levelMult = Math.max(0.0d, stateService.getState().getRewardTuning().getLevelUpCreditMultiplier());
		for (int level = previousLevel + 1; level <= currentLevel; level++)
		{
			totalReward += Math.round(levelUpReward(level) * levelMult);
		}

		if (totalReward > 0L)
		{
			addCredits(totalReward);
		}
		return totalReward;
	}

	private void trackXpGainFromStatChanged(Skill skill, int currentXp)
	{
		if (isOverallSkill(skill))
		{
			return;
		}

		int skillIndex = skill.ordinal();
		if (skillIndex < 0 || skillIndex >= previousSkillXp.length)
		{
			return;
		}

		int previousXp = previousSkillXp[skillIndex];
		// Skill XP cannot decrease; never lower the baseline (avoids disconnect false awards).
		if (currentXp < previousXp)
		{
			debugAward(String.format(
				"Ignored skill XP drop for %s (%s -> %s); keeping baseline",
				skill.getName(), NumberFormatting.format(previousXp), NumberFormatting.format(currentXp)));
			return;
		}

		if (currentXp == previousXp)
		{
			return;
		}

		if (skillXpInitialized)
		{
			long xpGained = (long) currentXp - previousXp;
			if (isCombatSkill(skill))
			{
				debugAward(String.format(
					"Ignored +%s combat skill XP (%s)",
					NumberFormatting.format(xpGained), skill.getName()));
			}
			else if (!isCreditAwardOnCooldown())
			{
				applyXpGain(xpGained, skill.getName());
			}
		}
		previousSkillXp[skillIndex] = currentXp;
	}

	private void applyXpGain(long xpGained, String source)
	{
		if (xpGained <= 0L)
		{
			return;
		}

		long nextUncreditedXp = uncreditedXp + xpGained;
		debugAward(String.format("Registered +%s XP (%s) -> uncredited pool %s / %s",
			NumberFormatting.format(xpGained), safeName(source),
			NumberFormatting.format(nextUncreditedXp), NumberFormatting.format(XP_PER_CREDIT_CHUNK)));

		uncreditedXp = nextUncreditedXp;
		awardCreditsFromUncreditedXp(source);
		persistSkillBaselineToState(false);
	}

	/** @return true if credits were added from XP chunks */
	private boolean awardCreditsFromUncreditedXp(String source)
	{
		long chunks = uncreditedXp / XP_PER_CREDIT_CHUNK;
		if (chunks <= 0L)
		{
			return false;
		}

		double mult = Math.max(0.0d, stateService.getState().getRewardTuning().getXpCreditMultiplier());
		long credits = Math.round((double) (chunks * CREDITS_PER_CHUNK) * mult);
		long xpCredited = chunks * XP_PER_CREDIT_CHUNK;
		if (credits <= 0L)
		{
			uncreditedXp -= xpCredited;
			return false;
		}

		addCredits(credits);
		uncreditedXp -= xpCredited;
		debugAward(String.format("XP drop +%s (%s) -> +%s credits (total %s)",
			NumberFormatting.format(xpCredited), safeName(source),
			NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
		return true;
	}

	private void addCredits(long credits)
	{
		if (credits <= 0L)
		{
			return;
		}

		persistSkillBaselineToState(false);
		stateService.addCredits(credits);
	}

	/**
	 * Writes the current in-memory skill baselines into profile state.
	 *
	 * @param save whether to flush to disk immediately
	 */
	private void persistSkillBaselineToState(boolean save)
	{
		if (!skillXpInitialized)
		{
			return;
		}

		SkillCreditBaseline baseline = SkillCreditBaseline.fromClientExperiences(
			Arrays.copyOf(previousSkillXp, previousSkillXp.length),
			uncreditedXp);
		stateService.replaceSkillCreditBaseline(baseline);
		if (save)
		{
			stateService.save();
		}
	}

	private void suppressCreditAwardsUntilStatsSettle(boolean clearUncreditedXpPool)
	{
		beginCreditAwardCooldown();
		resetSkillCreditTracking(clearUncreditedXpPool);
	}

	private void beginCreditAwardCooldown()
	{
		creditAwardCooldownActive = true;
		if (client == null)
		{
			creditAwardCooldownUntilTick = 0;
			return;
		}

		creditAwardCooldownUntilTick = client.getTickCount() + CREDIT_AWARD_COOLDOWN_TICKS;
	}

	private boolean isCreditAwardOnCooldown()
	{
		if (!creditAwardCooldownActive || client == null)
		{
			return false;
		}

		int tick = client.getTickCount();
		if (tick >= creditAwardCooldownUntilTick)
		{
			return false;
		}

		// Cooldown armed before the tick counter reset (e.g. at the login screen).
		if (creditAwardCooldownUntilTick - tick > CREDIT_AWARD_COOLDOWN_TICKS)
		{
			return false;
		}

		return true;
	}

	private void resetSkillCreditTracking(boolean clearUncreditedXpPool)
	{
		lastKnownLevels.clear();
		skillLevelsInitialized = false;
		skillXpInitialized = false;
		if (clearUncreditedXpPool)
		{
			clearUncreditedXpPool("login or logout");
		}
		Arrays.fill(previousSkillXp, 0);
	}

	private void clearUncreditedXpPool(String reason)
	{
		if (uncreditedXp <= 0L)
		{
			return;
		}

		debugAward(String.format(
			"Uncredited XP pool cleared (%s); lost %s XP toward next chunk",
			reason, NumberFormatting.format(uncreditedXp)));
		uncreditedXp = 0L;
	}

	private void snapshotSkillBaselinesIfLoggedIn()
	{
		snapshotSkillExperiencesIfLoggedIn();
		snapshotSkillLevelsIfLoggedIn();
	}

	private void snapshotSkillExperiencesIfLoggedIn()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int[] experiences = client.getSkillExperiences();
		int n = Math.min(experiences.length, previousSkillXp.length);
		if (!skillXpInitialized)
		{
			System.arraycopy(experiences, 0, previousSkillXp, 0, n);
		}
		else
		{
			// Never lower an already-established baseline from a transient client snapshot.
			for (int i = 0; i < n; i++)
			{
				if (experiences[i] > previousSkillXp[i])
				{
					previousSkillXp[i] = experiences[i];
				}
			}
		}
		skillXpInitialized = true;
	}

	private void snapshotSkillLevelsIfLoggedIn()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (!skillLevelsInitialized)
		{
			lastKnownLevels.clear();
		}
		for (Skill skill : Skill.values())
		{
			if (isOverallSkill(skill))
			{
				continue;
			}

			int level = levelForXp(client.getSkillExperience(skill));
			Integer previous = lastKnownLevels.get(skill);
			if (previous == null || level > previous)
			{
				lastKnownLevels.put(skill, level);
			}
		}
		skillLevelsInitialized = true;
	}

	private int applyKillCreditTuning(int baseLevel)
	{
		double scaled = baseLevel * stateService.getState().getRewardTuning().getKillCreditMultiplier();
		return Math.max(0, (int) Math.round(scaled));
	}

	private int levelUpReward(int level)
	{
		int clamped = clampLevel(level);
		if (clamped <= 2)
		{
			return LEVEL_UP_REWARD_FLOOR;
		}
		if (clamped >= Experience.MAX_REAL_LEVEL)
		{
			return LEVEL_UP_REWARD_CAP;
		}

		double progress = (clamped - 2.0d) / LEVEL_UP_PROGRESS_LEVELS;
		double curve = Math.pow(progress, LEVEL_UP_CURVE_STEEPNESS);
		double multiplier = Math.pow((double) LEVEL_UP_REWARD_CAP / LEVEL_UP_REWARD_FLOOR, curve);
		return (int) Math.round(LEVEL_UP_REWARD_FLOOR * multiplier);
	}

	/** Virtual-aware skill level from total XP (1–126). */
	private int levelForXp(int xp)
	{
		return clampLevel(Experience.getLevelForXp(Math.max(0, xp)));
	}

	private int clampLevel(int level)
	{
		if (level < 1)
		{
			return 1;
		}
		return Math.min(level, Experience.MAX_VIRT_LEVEL);
	}

	private String safeName(String name)
	{
		return name == null || name.isEmpty() ? "Unknown NPC" : name;
	}

	private void debugAward(String message)
	{
		boolean chat = stateService.isDebugChatEnabled();
		boolean trace = stateService.isDebugTracingActive();
		if (!chat && !trace)
		{
			return;
		}

		log.info("[OSRS TCG] {}", message);
		if (chat)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSRS TCG] " + message, null);
		}
	}

	private boolean isOverallSkill(Skill skill)
	{
		return skill != null && "Overall".equalsIgnoreCase(skill.getName());
	}

	private boolean isCombatSkill(Skill skill)
	{
		return skill != null && COMBAT_SKILLS.contains(skill);
	}


	private boolean isGenuineMaxedSkillFakeXpDrop(Skill skill)
	{
		if (client == null || isOverallSkill(skill))
		{
			return false;
		}

		return client.getSkillExperience(skill) >= Experience.MAX_SKILL_XP;
	}
}

package com.osrstcg.service;

import com.osrstcg.util.NumberFormatting;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
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
	/** Level-up bonus when reaching level 99. */
	private static final int LEVEL_UP_REWARD_CAP = 25_000;
	private static final int LEVEL_UP_PROGRESS_LEVELS = 97;
	private static final double LEVEL_UP_CURVE_STEEPNESS = 2.5d;
	/** Ignore bogus fake XP drop payloads. */
	private static final int FAKE_XP_DROP_SANITY_CAP = 20_000_000;
	/** Suppress credit awards while stats settle after login or a world hop. */
	private static final int CREDIT_AWARD_COOLDOWN_TICKS = 15;

	private final Client client;
	private final TcgStateService stateService;
	private final Map<Skill, Integer> lastKnownRealLevels = new EnumMap<>(Skill.class);
	private final int[] previousSkillXp = new int[Skill.values().length];
	private boolean skillLevelsInitialized;
	private boolean skillXpInitialized;
	private boolean creditAwardCooldownActive;
	private int creditAwardCooldownUntilTick;
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
	 */
	public void resetExperienceCreditBaseline()
	{
		clearUncreditedXpPool("profile change");
		skillXpInitialized = false;
		Arrays.fill(previousSkillXp, 0);
		snapshotSkillBaselinesIfLoggedIn();
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

		trackXpGainFromStatChanged(skill, event.getXp());

		if (isCreditAwardOnCooldown())
		{
			return;
		}

		if (isOverallSkill(skill))
		{
			return;
		}

		int current = clampLevel(client.getRealSkillLevel(skill));
		if (!skillLevelsInitialized || !lastKnownRealLevels.containsKey(skill))
		{
			lastKnownRealLevels.put(skill, current);
			return;
		}

		int previous = lastKnownRealLevels.get(skill);

		if (current <= previous)
		{
			lastKnownRealLevels.put(skill, current);
			return;
		}

		long totalReward = 0L;
		double levelMult = Math.max(0.0d, stateService.getState().getRewardTuning().getLevelUpCreditMultiplier());
		for (int level = previous + 1; level <= current; level++)
		{
			totalReward += Math.round(levelUpReward(level) * levelMult);
		}

		lastKnownRealLevels.put(skill, current);
		if (totalReward > 0)
		{
			addCredits(totalReward);
			debugAward(String.format("Level up %s: %d -> %d -> +%s credits (total %s)",
				skill.getName(), previous, current, NumberFormatting.format(totalReward), NumberFormatting.format(stateService.getCredits())));
		}
	}

	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (event == null || event.getSkill() == null || isCreditAwardOnCooldown())
		{
			return;
		}

		Skill skill = event.getSkill();
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
		if (client != null && client.getGameState() == GameState.LOGGED_IN)
		{
			suppressCreditAwardsUntilStatsSettle(false);
		}
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		GameState next = event.getGameState();
		GameState previous = lastObservedGameState;
		lastObservedGameState = next;

		if (next == GameState.LOGIN_SCREEN)
		{
			suppressCreditAwardsUntilStatsSettle(true);
			return;
		}

		if (next == GameState.HOPPING)
		{
			suppressCreditAwardsUntilStatsSettle(false);
			return;
		}

		if (next == GameState.LOGGED_IN
			&& (previous == GameState.LOGIN_SCREEN || previous == GameState.HOPPING || previous == GameState.LOADING))
		{
			suppressCreditAwardsUntilStatsSettle(previous == GameState.LOGIN_SCREEN);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!creditAwardCooldownActive || isCreditAwardOnCooldown())
		{
			return;
		}

		creditAwardCooldownActive = false;
		snapshotSkillBaselinesIfLoggedIn();
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
		if (skillXpInitialized && currentXp > previousXp)
		{
			applyXpGain(currentXp - previousXp, skill.getName());
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
		debugAward(String.format("XP drop +%s (%s, chunks of %s) -> +%s credits (total %s)",
			NumberFormatting.format(xpCredited), safeName(source), NumberFormatting.format(XP_PER_CREDIT_CHUNK),
			NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
		return true;
	}

	private void addCredits(long credits)
	{
		if (credits <= 0L)
		{
			return;
		}

		stateService.addCredits(credits);
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
		return creditAwardCooldownActive && client != null && client.getTickCount() < creditAwardCooldownUntilTick;
	}

	private void resetSkillCreditTracking(boolean clearUncreditedXpPool)
	{
		lastKnownRealLevels.clear();
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
		System.arraycopy(experiences, 0, previousSkillXp, 0, Math.min(experiences.length, previousSkillXp.length));
		skillXpInitialized = true;
	}

	private void snapshotSkillLevelsIfLoggedIn()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		lastKnownRealLevels.clear();
		for (Skill skill : Skill.values())
		{
			if (isOverallSkill(skill))
			{
				continue;
			}

			lastKnownRealLevels.put(skill, clampLevel(client.getRealSkillLevel(skill)));
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

		double progress = (clamped - 2.0d) / LEVEL_UP_PROGRESS_LEVELS;
		double curve = Math.pow(progress, LEVEL_UP_CURVE_STEEPNESS);
		double multiplier = Math.pow((double) LEVEL_UP_REWARD_CAP / LEVEL_UP_REWARD_FLOOR, curve);
		return (int) Math.round(LEVEL_UP_REWARD_FLOOR * multiplier);
	}

	private int clampLevel(int level)
	{
		if (level < 1)
		{
			return 1;
		}
		return Math.min(level, 99);
	}

	private String safeName(String name)
	{
		return name == null || name.isEmpty() ? "Unknown NPC" : name;
	}

	private void debugAward(String message)
	{
		if (!stateService.isDebugChatEnabled())
		{
			return;
		}

		log.info("[OSRS TCG] {}", message);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSRS TCG] " + message, null);
	}

	private boolean isOverallSkill(Skill skill)
	{
		return skill != null && "Overall".equalsIgnoreCase(skill.getName());
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

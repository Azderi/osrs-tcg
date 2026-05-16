package com.osrstcg.service;

import com.osrstcg.util.NumberFormatting;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;

@Singleton
@Slf4j
public class CreditAwardService
{
	private static final long XP_PER_CREDIT_CHUNK = 1000L;
	private static final long CREDITS_PER_CHUNK = 100L;

	private final Client client;
	private final TcgStateService stateService;
	private final Map<Skill, Integer> lastKnownRealLevels = new EnumMap<>(Skill.class);
	private boolean skillLevelsInitialized;

	/** Last overall XP used for chunk awards; {@code -1} until first snapshot after login. */
	private long lastOverallXpForCredits = -1L;

	/**
	 * Baseline must not latch onto a transient low {@link Client#getOverallExperience()} (often wrong for a
	 * tick or two after login, sometimes matching unrelated small numbers). Require the same reading on
	 * two different {@link Client#getTickCount()} values so multiple {@link StatChanged} calls in one tick
	 * cannot "stabilize" garbage.
	 */
	private static final long OVERALL_XP_BASELINE_NO_CANDIDATE = Long.MIN_VALUE;
	private static final int OVERALL_XP_BASELINE_MIN_TICKS = 2;
	private long overallXpBaselineCandidate = OVERALL_XP_BASELINE_NO_CANDIDATE;
	private int overallXpBaselineTicksSeen;
	private int overallXpBaselineLastTick = -1;

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
		lastOverallXpForCredits = -1L;
		clearOverallXpBaselineStaging();
	}

	public void awardNpcKillCredits(String npcName, int combatLevel)
	{
		if (combatLevel <= 0)
		{
			return;
		}

		int creditsPerKill = applyKillCreditTuning(combatLevel);
		long totalCredits = creditsPerKill;
		if (totalCredits <= 0)
		{
			return;
		}

		stateService.addCredits(totalCredits);
		debugAward(String.format("Killed %s (lvl %d) -> +%s credits (total %s)",
			safeName(npcName), combatLevel, NumberFormatting.format(totalCredits), NumberFormatting.format(stateService.getCredits())));
	}

	public boolean onGameTick(GameTick event)
	{
		return syncExperienceFromOverallXp();
	}

	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		if (skill == null)
		{
			syncExperienceFromOverallXp();
			return;
		}
		if (isOverallSkill(skill))
		{
			syncExperienceFromOverallXp();
			return;
		}

		int current = clampLevel(client.getRealSkillLevel(skill));
		if (!skillLevelsInitialized || !lastKnownRealLevels.containsKey(skill))
		{
			lastKnownRealLevels.put(skill, current);
			syncExperienceFromOverallXp();
			return;
		}

		int previous = lastKnownRealLevels.get(skill);

		if (current <= previous)
		{
			lastKnownRealLevels.put(skill, current);
			syncExperienceFromOverallXp();
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
			stateService.addCredits(totalReward);
			debugAward(String.format("Level up %s: %d -> %d -> +%s credits (total %s)",
				skill.getName(), previous, current, NumberFormatting.format(totalReward), NumberFormatting.format(stateService.getCredits())));
		}

		syncExperienceFromOverallXp();
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Defer baseline establishment to first StatChanged per-skill to avoid
			// login-time transients being interpreted as level-ups.
			lastKnownRealLevels.clear();
			skillLevelsInitialized = true;
			lastOverallXpForCredits = -1L;
			clearOverallXpBaselineStaging();
			return;
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			lastKnownRealLevels.clear();
			skillLevelsInitialized = false;
			lastOverallXpForCredits = -1L;
			clearOverallXpBaselineStaging();
		}
	}

	/** @return true if credits were added from XP chunks */
	private boolean syncExperienceFromOverallXp()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}

		long totalXp = (long) client.getOverallExperience();
		if (totalXp < 0L)
		{
			return false;
		}

		// Do not snapshot baseline on a single positive reading: after login the client can briefly report
		// 0, then a bogus low total, then the real total — baselining the low value makes the next tick look
		// like huge XP gain (~85M credits for ~850M XP). Require the same total on distinct game ticks.
		if (lastOverallXpForCredits < 0L)
		{
			if (totalXp <= 0L)
			{
				clearOverallXpBaselineStaging();
				return false;
			}
			int tick = client.getTickCount();
			if (totalXp != overallXpBaselineCandidate)
			{
				overallXpBaselineCandidate = totalXp;
				overallXpBaselineTicksSeen = 1;
				overallXpBaselineLastTick = tick;
				return false;
			}
			if (tick == overallXpBaselineLastTick)
			{
				return false;
			}
			overallXpBaselineTicksSeen++;
			overallXpBaselineLastTick = tick;
			if (overallXpBaselineTicksSeen < OVERALL_XP_BASELINE_MIN_TICKS)
			{
				return false;
			}
			lastOverallXpForCredits = totalXp;
			clearOverallXpBaselineStaging();
			return false;
		}

		if (totalXp < lastOverallXpForCredits)
		{
			lastOverallXpForCredits = totalXp;
			return false;
		}

		long gained = totalXp - lastOverallXpForCredits;
		long chunks = gained / XP_PER_CREDIT_CHUNK;
		if (chunks <= 0L)
		{
			return false;
		}

		double mult = Math.max(0.0d, stateService.getState().getRewardTuning().getXpCreditMultiplier());
		long credits = Math.round((double) (chunks * CREDITS_PER_CHUNK) * mult);
		if (credits <= 0L)
		{
			lastOverallXpForCredits += chunks * XP_PER_CREDIT_CHUNK;
			return false;
		}

		stateService.addCredits(credits);
		lastOverallXpForCredits += chunks * XP_PER_CREDIT_CHUNK;
		long xpCredited = chunks * XP_PER_CREDIT_CHUNK;
		debugAward(String.format("Overall XP +%s (chunks of %s) -> +%s credits (total %s)",
			NumberFormatting.format(xpCredited), NumberFormatting.format(XP_PER_CREDIT_CHUNK),
			NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
		return true;
	}

	private int applyKillCreditTuning(int baseLevel)
	{
		double scaled = baseLevel * stateService.getState().getRewardTuning().getKillCreditMultiplier();
		return Math.max(0, (int) Math.round(scaled));
	}

	private int levelUpReward(int level)
	{
		int clamped = clampLevel(level);
		double reward = 100.0d + (clamped - 1) * (25000.0d - 100.0d) / 98.0d;
		return (int) Math.round(reward);
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
		if (!stateService.isDebugLogging())
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

	private void clearOverallXpBaselineStaging()
	{
		overallXpBaselineCandidate = OVERALL_XP_BASELINE_NO_CANDIDATE;
		overallXpBaselineTicksSeen = 0;
		overallXpBaselineLastTick = -1;
	}
}

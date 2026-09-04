package com.osrstcg.credit;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.state.SkillCreditBaseline;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.eventbus.Subscribe;
import com.osrstcg.state.TcgStateService;
/**
 * Central coordinator for awarding credits from XP gains and level-ups. Tracks per-skill XP/level baselines,
 * converts XP into credit chunks (with a separate, cheaper Slayer conversion), and enqueues optimistic
 * credit attest events via {@link CreditAttestQueue}. Also enforces a short cooldown after login/world-hop
 * so transient stat resyncs (e.g. temporary boosts settling) don't get credited as real gains. Most XP/level
 * entry points ({@link #onStatChanged}, {@link #onFakeXpDrop}) are called directly from the plugin's event
 * handlers; {@link #onGameTick} is the only RuneLite-subscribed method here.
 */
@Singleton
@Slf4j
public class CreditAwardService
{
	private static final int FAKE_XP_DROP_SANITY_CAP = 20_000_000;
/** Default credit-award cooldown, in game ticks, after login/world-hop before live XP gains are credited. */
	static final int CREDIT_COOLDOWN_TICKS = 3;
/** Extra settle window when hopping off a restricted/event world (temp max stats). */
	static final int RESTRICTED_WORLD_EXIT_SETTLE_TICKS = 15;
/** Combat skills excluded from XP-based credit awards (XP here comes from combat, not standalone training). */
	private static final Set<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK,
		Skill.DEFENCE,
		Skill.STRENGTH,
		Skill.MAGIC,
		Skill.RANGED
	);

	private final Client client;
	private final TcgStateService stateService;
	private final CloudSessionService session;
	private final CreditAttestQueue attestQueue;
	private final ChatMessageManager chatMessageManager;
	private final SkillCreditSession skills = new SkillCreditSession();
	private boolean creditCooldownActive;
	private int creditCooldownUntilTick;
	private int creditCooldownDurationTicks = CREDIT_COOLDOWN_TICKS;
	private boolean pendingStatsSettle;
	private boolean restoreXpFromPersistedBaseline;

	@Inject
	public CreditAwardService(Client client, TcgStateService stateService, CloudSessionService session,
		CreditAttestQueue attestQueue, ChatMessageManager chatMessageManager)
	{
		this.client = client;
		this.stateService = stateService;
		this.session = session;
		this.attestQueue = attestQueue;
		this.chatMessageManager = chatMessageManager;
	}
/** Resets in-memory tracking and restores the uncredited XP pool from the persisted baseline, if any. */
	public void resetExperienceCreditBaseline()
	{
		skills.resetTracking();

		SkillCreditBaseline saved = presentBaseline();
		if (saved != null)
		{
			skills.restoreUncreditedXp(saved);
		}
		else
		{
			clearUncreditedXpPool("profile change");
		}
	}
/** Snapshots current skill baselines (if logged in) and persists them, when credit tracking is allowed. */
	public void flushSkillBaselineForPersist()
	{
		if (!isCreditTrackingAllowed())
		{
			return;
		}
		skills.snapshotBaselinesIfLoggedIn(client);
		persistSkillBaselineToState();
	}
/** Whether credit tracking is currently allowed (false while the cloud account is locked). */
	public boolean isCreditTrackingAllowed()
	{
		return !session.isAccountLocked();
	}
/** Stops tracking on an account lock: clears uncredited XP and resets in-memory baselines to zero. */
	public void stopCreditTrackingOnLock()
	{
		clearUncreditedXpPool("account locked");
		skills.resetTracking();
		SkillCreditBaseline saved = presentBaseline();
		if (saved != null && !saved.getUncreditedXpBySkill().isEmpty())
		{
			stateService.replaceSkillCreditBaseline(
				SkillCreditBaseline.of(saved.getSkillXpByName(), Map.of()));
		}
	}
/**
	 * Handles a {@code StatChanged} event: tracks XP gain for credit chunks and, outside cooldown, checks
	 * for a level-up to award level-up credit.
	 *
	 * @return true if this call caused an XP chunk to be credited
	 */
	public boolean onStatChanged(StatChanged event)
	{
		if (!isCreditTrackingAllowed())
		{
			return false;
		}

		Skill skill = event.getSkill();
		if (skill == null)
		{
			return false;
		}

		int currentXp = event.getXp();
		boolean xpChunkAwarded = trackXpGainFromStatChanged(skill, currentXp);

		if (isCreditAwardOnCooldown())
		{
			return xpChunkAwarded;
		}

		if (isOverallSkill(skill))
		{
			return xpChunkAwarded;
		}

		int current = LevelUpCreditMath.levelForXp(currentXp);
		if (!skills.skillLevelsInitialized || !skills.lastKnownLevels.containsKey(skill))
		{
			skills.lastKnownLevels.put(skill, current);
			return xpChunkAwarded;
		}

		int previous = skills.lastKnownLevels.get(skill);

		if (current <= previous)
		{
			return xpChunkAwarded;
		}

		awardLevelUps(skill, previous, current);
		skills.lastKnownLevels.put(skill, current);
		return xpChunkAwarded;
	}
/**
	 * Handles a {@code FakeXpDrop} event (XP that doesn't reach {@code StatChanged}, e.g. once a skill is
	 * maxed). Ignores combat-skill drops and drops for skills not already at max XP; Hitpoints XP is attested
	 * without going into the creditable XP bucket.
	 *
	 * @return true if this call caused credit to be awarded
	 */
	public boolean onFakeXpDrop(FakeXpDrop event)
	{
		if (!isCreditTrackingAllowed())
		{
			return false;
		}

		if (event == null || event.getSkill() == null || isCreditAwardOnCooldown())
		{
			return false;
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
			return false;
		}

		if (!isGenuineMaxedSkillFakeXpDrop(skill))
		{
			debugAward(String.format(
				"Ignored fake XP drop for %s (skill below %s XP)",
				skill.getName(), NumberFormatting.format(Experience.MAX_SKILL_XP)));
			return false;
		}

		int xp = event.getXp();
		if (xp <= 0 || xp >= FAKE_XP_DROP_SANITY_CAP)
		{
			return false;
		}

		if (skill == Skill.HITPOINTS)
		{
			attestXpWithoutCreditBucket(xp, skill.getName() + " drop");
			return false;
		}

		return applyXpGain(xp, skill);
	}
/** Arms the settle cooldown appropriate to the client's current game state when the plugin starts up. */
	public void onPluginStarted()
	{
		if (client == null)
		{
			return;
		}

		GameState current = client.getGameState();
		if (current == GameState.LOGIN_SCREEN)
		{
			armStatsSettleForLoginScreen();
		}
		else if (current == GameState.HOPPING || current == GameState.LOGGED_IN)
		{
			armStatsSettleForHopOrLogin();
		}
	}
/**
	 * Handles a {@code GameStateChanged} event: persists baselines and arms a settle cooldown on logout or
	 * world hop, and begins the credit cooldown once logged back in if a settle was pending.
	 */
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState next = event.getGameState();

		if (next == GameState.LOGIN_SCREEN)
		{
			persistSkillBaselineToState();
			armStatsSettleForLoginScreen();
			return;
		}

		if (next == GameState.HOPPING)
		{
			persistSkillBaselineToState();
			armStatsSettleForHopOrLogin();
			return;
		}

		if (next != GameState.LOGGED_IN)
		{
			return;
		}

		if (pendingStatsSettle)
		{
			beginCreditAwardCooldown(creditCooldownDurationTicks);
		}
	}
/**
	 * Drives the credit-award cooldown and baseline initialization each tick: ends an expired cooldown and
	 * re-captures baselines after settling, or performs first-time baseline capture if not yet initialized.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!isCreditTrackingAllowed())
		{
			return;
		}

		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (creditCooldownActive)
		{
			if (isCreditAwardOnCooldown())
			{
				return;
			}

			creditCooldownActive = false;
			pendingStatsSettle = false;
			captureBaselinesAfterSettle();
			debugAward("Credit award cooldown ended; resuming live credit gains");
			return;
		}

		if (!skills.skillXpInitialized || !skills.skillLevelsInitialized)
		{
			skills.snapshotBaselinesIfLoggedIn(client);
			persistSkillBaselineToState();
		}
	}
/** Restores any persisted uncredited XP (if pending) and (re-)captures live skill baselines after settling. */
	private void captureBaselinesAfterSettle()
	{
		if (restoreXpFromPersistedBaseline)
		{
			skills.restoreUncreditedXp(presentBaseline());
			restoreXpFromPersistedBaseline = false;
		}

		if (!skills.skillXpInitialized || !skills.skillLevelsInitialized)
		{
			skills.snapshotBaselinesIfLoggedIn(client);
		}
		persistSkillBaselineToState();
		debugAward("Live skill baselines captured after settle");
	}
/**
	 * Sums the level-up reward (credits) for each level from {@code previousLevel+1} to {@code currentLevel}
	 * and enqueues an attest event for the total, if the cloud session can currently collect attests.
	 *
	 * @return the total credits enqueued (0 if none, or if attests can't be collected right now)
	 */
	private long awardLevelUps(Skill skill, int previousLevel, int currentLevel)
	{
		if (currentLevel <= previousLevel)
		{
			return 0L;
		}

		long totalReward = 0L;
		for (int level = previousLevel + 1; level <= currentLevel; level++)
		{
			totalReward += LevelUpCreditMath.levelUpReward(level);
		}

		if (totalReward <= 0L)
		{
			return 0L;
		}

		if (!session.canCollectAttests())
		{
			debugAward(String.format("Cloud offline; discarding level up %s -> %d..%d credit reward",
				skill.getName(), previousLevel, currentLevel));
			return 0L;
		}

		persistSkillBaselineToState();
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill.getName());
		evidence.addProperty("fromLevel", previousLevel);
		evidence.addProperty("toLevel", currentLevel);
		attestQueue.enqueue("level_up", evidence, totalReward);

		debugAward(String.format("Level up %s: %d -> %d -> +%s credits (total %s)",
			skill.getName(), previousLevel, currentLevel,
			NumberFormatting.format(totalReward), NumberFormatting.format(stateService.getCredits())));
		return totalReward;
	}
/**
	 * Updates the previous-XP baseline for {@code skill} and, if XP increased while not on cooldown, routes
	 * the gain to the appropriate credit path (ignored for combat skills, Hitpoints attested without credit
	 * bucketing, others accumulated toward an XP chunk). XP drops (e.g. from a stat reset) are ignored.
	 *
	 * @return true if this call caused an XP chunk to be credited
	 */
	private boolean trackXpGainFromStatChanged(Skill skill, int currentXp)
	{
		if (isOverallSkill(skill))
		{
			return false;
		}

		int skillIndex = skill.ordinal();
		if (skillIndex < 0 || skillIndex >= skills.previousSkillXp.length)
		{
			return false;
		}

		int previousXp = skills.previousSkillXp[skillIndex];
		if (currentXp < previousXp)
		{
			debugAward(String.format(
				"Ignored skill XP drop for %s (%s -> %s); keeping baseline",
				skill.getName(), NumberFormatting.format(previousXp), NumberFormatting.format(currentXp)));
			return false;
		}

		if (currentXp == previousXp)
		{
			return false;
		}

		boolean xpChunkAwarded = false;
		if (skills.skillXpInitialized)
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
				if (skill == Skill.HITPOINTS)
				{
					attestXpWithoutCreditBucket(xpGained, skill.getName());
				}
				else
				{
					xpChunkAwarded = applyXpGain(xpGained, skill);
				}
			}
		}
		skills.previousSkillXp[skillIndex] = currentXp;
		return xpChunkAwarded;
	}
/**
	 * Applies a positive XP gain (xp) for {@code skill}: routes Slayer XP through its own chunk conversion,
	 * otherwise pools the XP into the skill's uncredited bucket and awards any completed chunks.
	 *
	 * @return true if this call caused an XP chunk to be credited
	 */
	private boolean applyXpGain(long xpGained, Skill skill)
	{
		if (xpGained <= 0L || skill == null)
		{
			return false;
		}
		if (skill == Skill.SLAYER)
		{
			return attestSlayerXp(xpGained, skill.getName());
		}

		long nextUncreditedXp = skills.addUncreditedXp(skill, xpGained);
		debugAward(String.format("Registered +%s XP (%s) -> %s / %s",
			NumberFormatting.format(xpGained), skill.getName(),
			NumberFormatting.format(nextUncreditedXp), NumberFormatting.format(XpCreditMath.XP_PER_CREDIT_CHUNK)));

		boolean awarded = awardCreditsFromUncreditedXp(skill);
		persistSkillBaselineToState();
		return awarded;
	}
/** Attests {@code xpGained} (xp) as an XP chunk with zero optimistic credits (e.g. Hitpoints XP). */
	private void attestXpWithoutCreditBucket(long xpGained, String source)
	{
		if (xpGained <= 0L)
		{
			return;
		}
		if (!session.canCollectAttests())
		{
			debugAward(String.format("Cloud offline; +%s XP (%s) not attested",
				NumberFormatting.format(xpGained), safeName(source)));
			return;
		}

		enqueueXpChunk(source, xpGained, 0L);
		debugAward(String.format("Registered +%s XP (%s) (ignored)",
			NumberFormatting.format(xpGained), safeName(source)));
	}
/**
	 * Accumulates Slayer XP (xp) and, if attests can currently be collected, converts completed
	 * {@link XpCreditMath#SLAYER_XP_PER_CHUNK} chunks to credits and attests the pending amount. If the
	 * cloud session is offline, the XP stays pending and nothing is attested yet.
	 *
	 * @return true if this call caused credit to be awarded
	 */
	private boolean attestSlayerXp(long xpGained, String source)
	{
		if (xpGained <= 0L)
		{
			return false;
		}

		skills.pendingSlayerXpToAttest += xpGained;
		debugAward(String.format("Registered +%s XP (%s) -> pending attest %s (bucket %s)",
			NumberFormatting.format(xpGained), safeName(source),
			NumberFormatting.format(skills.pendingSlayerXpToAttest),
			NumberFormatting.format(XpCreditMath.SLAYER_XP_PER_CHUNK)));

		if (!session.canCollectAttests())
		{
			debugAward(String.format("Cloud offline; +%s Slayer XP pending until reconnected",
				NumberFormatting.format(xpGained)));
			persistSkillBaselineToState();
			return false;
		}

		long toSend = skills.pendingSlayerXpToAttest;
		skills.pendingSlayerXpToAttest = 0L;
		skills.slayerXpRemainder += toSend;
		long chunks = skills.slayerXpRemainder / XpCreditMath.SLAYER_XP_PER_CHUNK;
		long credits = chunks * XpCreditMath.SLAYER_CREDITS_PER_CHUNK;
		skills.slayerXpRemainder -= chunks * XpCreditMath.SLAYER_XP_PER_CHUNK;

		persistSkillBaselineToState();
		enqueueXpChunk(source, toSend, credits);
		debugAward(String.format("XP drop +%s (%s) -> +%s credits (total %s)",
			NumberFormatting.format(toSend), safeName(source),
			NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
		return credits > 0L;
	}
/**
	 * Converts completed XP chunks from the skill's uncredited pool into credits, attests them, and
	 * subtracts the credited XP from the pool, if attests can currently be collected.
	 *
	 * @return true if this call caused credit to be awarded
	 */
	private boolean awardCreditsFromUncreditedXp(Skill skill)
	{
		if (skill == null)
		{
			return false;
		}

		long remainder = skills.uncreditedXpFor(skill);
		long chunks = remainder / XpCreditMath.XP_PER_CREDIT_CHUNK;
		if (chunks <= 0L)
		{
			return false;
		}

		long xpCredited = chunks * XpCreditMath.XP_PER_CREDIT_CHUNK;
		long credits = chunks * XpCreditMath.CREDITS_PER_CHUNK;

		if (!session.canCollectAttests())
		{
			debugAward(String.format("Cloud offline; +%s XP (%s) pending until reconnected",
				NumberFormatting.format(xpCredited), skill.getName()));
			return false;
		}

		persistSkillBaselineToState();
		enqueueXpChunk(skill.getName(), xpCredited, credits);
		skills.subtractUncreditedXp(skill, xpCredited);
		debugAward(String.format("XP drop +%s (%s) -> +%s credits (total %s)",
			NumberFormatting.format(xpCredited), skill.getName(),
			NumberFormatting.format(credits), NumberFormatting.format(stateService.getCredits())));
		return credits > 0L;
	}
/** Enqueues an {@code xp_chunk} attest event with {@code xpDelta} xp and its optimistic credits. */
	private void enqueueXpChunk(String skill, long xpDelta, long optimisticCredits)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill == null ? "" : skill);
		evidence.addProperty("xpDelta", xpDelta);
		attestQueue.enqueue("xp_chunk", evidence, optimisticCredits);
	}
/** Persisted skill credit baseline, if one exists and is non-empty; {@code null} otherwise. */
	private SkillCreditBaseline presentBaseline()
	{
		SkillCreditBaseline saved = stateService.getState().getSkillCreditBaseline();
		return saved != null && saved.isPresent() ? saved : null;
	}
/** Arms a settle cooldown for returning to the login screen, restoring the persisted uncredited XP after. */
	private void armStatsSettleForLoginScreen()
	{
		pendingStatsSettle = true;
		restoreXpFromPersistedBaseline = true;
		suppressAwardsUntilSettle(true, CREDIT_COOLDOWN_TICKS);
	}
/** Arms a settle cooldown for a world hop or re-login, keeping the uncredited XP pool intact. */
	private void armStatsSettleForHopOrLogin()
	{
		pendingStatsSettle = true;
		restoreXpFromPersistedBaseline = false;
		suppressAwardsUntilSettle(false, resolveHopSettleCooldownTicks(session.isRestrictedWorld()));
	}
/** Cooldown duration (ticks) to use after a world hop: longer when leaving a restricted/event world. */
	static int resolveHopSettleCooldownTicks(boolean restrictedWorld)
	{
		return restrictedWorld ? RESTRICTED_WORLD_EXIT_SETTLE_TICKS : CREDIT_COOLDOWN_TICKS;
	}
/** Persists the current skill baselines to plugin state, if tracking is allowed and XP is initialized. */
	private void persistSkillBaselineToState()
	{
		if (!isCreditTrackingAllowed() || !skills.skillXpInitialized)
		{
			return;
		}

		SkillCreditBaseline baseline = skills.toBaseline();
		stateService.replaceSkillCreditBaseline(baseline);
	}
/** Begins the credit cooldown and resets live tracking, optionally also clearing uncredited XP. */
	private void suppressAwardsUntilSettle(boolean clearUncreditedXpPool, int cooldownTicks)
	{
		beginCreditAwardCooldown(cooldownTicks);
		skills.resetTracking();
		if (clearUncreditedXpPool)
		{
			clearUncreditedXpPool("login or logout");
		}
	}
/** Starts (or restarts) the credit-award cooldown for {@code durationTicks} game ticks (min 1). */
	private void beginCreditAwardCooldown(int durationTicks)
	{
		int duration = Math.max(1, durationTicks);
		creditCooldownActive = true;
		creditCooldownDurationTicks = duration;
		if (client == null)
		{
			creditCooldownUntilTick = 0;
			return;
		}

		creditCooldownUntilTick = client.getTickCount() + duration;
	}
/** Whether the credit-award cooldown is currently active (also guards against tick-count rollover). */
	public boolean isCreditAwardOnCooldown()
	{
		if (!creditCooldownActive || client == null)
		{
			return false;
		}

		int tick = client.getTickCount();
		if (tick >= creditCooldownUntilTick)
		{
			return false;
		}

		if (creditCooldownUntilTick - tick > creditCooldownDurationTicks)
		{
			return false;
		}

		return true;
	}
/** Clears the uncredited XP pool, logging a debug message with {@code reason} if XP was actually lost. */
	private void clearUncreditedXpPool(String reason)
	{
		long totalRemainder = skills.totalUncreditedXp();
		if (totalRemainder > 0L)
		{
			debugAward(String.format(
				"Uncredited XP pool cleared (%s); lost %s XP toward next chunk",
				reason, NumberFormatting.format(totalRemainder)));
		}
		skills.clearUncreditedXpPool();
	}
/** Non-null NPC/source name for logging, defaulting to "Unknown NPC". */
	private String safeName(String name)
	{
		return name == null || name.isEmpty() ? "Unknown NPC" : name;
	}
/** Logs and chats {@code message} as a debug game message, when debug chat is enabled. */
	private void debugAward(String message)
	{
		if (!stateService.isDebugChatEnabled())
		{
			return;
		}
		log.info("[TCG DEBUG] {}", message);
		TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, message);
	}
/** Whether {@code skill} is the "Overall" pseudo-skill (excluded from XP/level credit tracking). */
	static boolean isOverallSkill(Skill skill)
	{
		return skill != null && "Overall".equalsIgnoreCase(skill.getName());
	}
/** Whether {@code skill} is one of {@link #COMBAT_SKILLS}. */
	private boolean isCombatSkill(Skill skill)
	{
		return skill != null && COMBAT_SKILLS.contains(skill);
	}
/** Whether {@code skill} is genuinely at max XP client-side, so a fake XP drop for it can be trusted. */
	private boolean isGenuineMaxedSkillFakeXpDrop(Skill skill)
	{
		if (client == null || isOverallSkill(skill))
		{
			return false;
		}

		return client.getSkillExperience(skill) >= Experience.MAX_SKILL_XP;
	}
}

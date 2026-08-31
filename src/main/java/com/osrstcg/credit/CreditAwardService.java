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

@Singleton
@Slf4j
public class CreditAwardService
{
	private static final int FAKE_XP_DROP_SANITY_CAP = 20_000_000;
	private static final int CREDIT_COOLDOWN_TICKS = 3;
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

	public void flushSkillBaselineForPersist()
	{
		if (!isCreditTrackingAllowed())
		{
			return;
		}
		skills.snapshotBaselinesIfLoggedIn(client);
		persistSkillBaselineToState();
	}

	public boolean isCreditTrackingAllowed()
	{
		return !session.isAccountLocked();
	}

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
			beginCreditAwardCooldown();
		}
	}

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

	private void enqueueXpChunk(String skill, long xpDelta, long optimisticCredits)
	{
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", skill == null ? "" : skill);
		evidence.addProperty("xpDelta", xpDelta);
		attestQueue.enqueue("xp_chunk", evidence, optimisticCredits);
	}

	private SkillCreditBaseline presentBaseline()
	{
		SkillCreditBaseline saved = stateService.getState().getSkillCreditBaseline();
		return saved != null && saved.isPresent() ? saved : null;
	}

	private void armStatsSettleForLoginScreen()
	{
		pendingStatsSettle = true;
		restoreXpFromPersistedBaseline = true;
		suppressAwardsUntilSettle(true);
	}

	private void armStatsSettleForHopOrLogin()
	{
		pendingStatsSettle = true;
		restoreXpFromPersistedBaseline = false;
		suppressAwardsUntilSettle(false);
	}

	private void persistSkillBaselineToState()
	{
		if (!isCreditTrackingAllowed() || !skills.skillXpInitialized)
		{
			return;
		}

		SkillCreditBaseline baseline = skills.toBaseline();
		stateService.replaceSkillCreditBaseline(baseline);
	}

	private void suppressAwardsUntilSettle(boolean clearUncreditedXpPool)
	{
		beginCreditAwardCooldown();
		skills.resetTracking();
		if (clearUncreditedXpPool)
		{
			clearUncreditedXpPool("login or logout");
		}
	}

	private void beginCreditAwardCooldown()
	{
		creditCooldownActive = true;
		if (client == null)
		{
			creditCooldownUntilTick = 0;
			return;
		}

		creditCooldownUntilTick = client.getTickCount() + CREDIT_COOLDOWN_TICKS;
	}

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

		if (creditCooldownUntilTick - tick > CREDIT_COOLDOWN_TICKS)
		{
			return false;
		}

		return true;
	}

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
		log.info("[TCG DEBUG] {}", message);
		TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, message);
	}

	static boolean isOverallSkill(Skill skill)
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

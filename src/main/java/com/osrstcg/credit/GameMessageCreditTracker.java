package com.osrstcg.credit;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.activity.CompiledActivityConfig;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.ui.SidebarRefresh;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;
import com.osrstcg.state.TcgStateService;

/**
 * Awards credits for activities that cannot use {@link NpcKillCreditTracker}.
 * Matchers and amounts come from server activity config ({@link ActivityConfigService}).
 */
@Slf4j
@Singleton
public final class GameMessageCreditTracker
{
	/**
	 * Boss KC / completion lines use {@link ChatMessageType#GAMEMESSAGE} by default, but
	 * {@link ChatMessageType#SPAM} when the in-game "Filter out boss kill-count with spam-filter" setting is on.
	 */
	private static final Set<ChatMessageType> CREDIT_CHAT_TYPES = EnumSet.of(
		ChatMessageType.GAMEMESSAGE,
		ChatMessageType.SPAM);

	private final CreditAwardService creditAwardService;
	private final CreditAttestQueue attestQueue;
	private final ActivityConfigService activityConfigService;
	private final TcgStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final SidebarRefresh sidebarRefresh;

	@Inject
	GameMessageCreditTracker(
		CreditAwardService creditAwardService,
		CreditAttestQueue attestQueue,
		ActivityConfigService activityConfigService,
		TcgStateService stateService,
		ChatMessageManager chatMessageManager,
		SidebarRefresh sidebarRefresh)
	{
		this.creditAwardService = creditAwardService;
		this.attestQueue = attestQueue;
		this.activityConfigService = activityConfigService;
		this.stateService = stateService;
		this.chatMessageManager = chatMessageManager;
		this.sidebarRefresh = sidebarRefresh;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event == null || !CREDIT_CHAT_TYPES.contains(event.getType())
			|| !creditAwardService.isCreditTrackingAllowed()
			|| creditAwardService.isCreditAwardOnCooldown())
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		Optional<CompiledActivityConfig.CompiledChatRule> rule = firstMatchingRule(message);
		if (rule.isEmpty())
		{
			return;
		}

		CompiledActivityConfig.CompiledChatRule matched = rule.get();
		JsonObject evidence = new JsonObject();
		evidence.addProperty("activityId", matched.getActivityId());
		attestQueue.enqueue("activity", evidence, matched.getCredits());
		debugActivityQueued(matched);
		sidebarRefresh.refreshCredits();
	}

	private void debugActivityQueued(CompiledActivityConfig.CompiledChatRule matched)
	{
		boolean chat = stateService.isDebugChatEnabled();
		boolean trace = stateService.isDebugTracingActive();
		if (!chat && !trace)
		{
			return;
		}

		String label = matched.getLabel();
		String what = label == null || label.isBlank() ? matched.getActivityId() : label;
		String body = String.format(
			"Activity \"%s\" detected -> +%s credits queued",
			what,
			NumberFormatting.format(matched.getCredits()));
		log.info("[TCG DEBUG] {}", body);
		if (chat)
		{
			TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, body);
		}
	}

	private Optional<CompiledActivityConfig.CompiledChatRule> firstMatchingRule(String messageWithoutTags)
	{
		List<CompiledActivityConfig.CompiledChatRule> rules = activityConfigService.getChatRules();
		for (CompiledActivityConfig.CompiledChatRule rule : rules)
		{
			if (rule.matches(messageWithoutTags))
			{
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}
}

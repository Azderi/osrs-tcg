package com.osrstcg.credit;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.activity.CompiledActivityConfig;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.state.TcgStateService;
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

/**
 * Awards credits for configured chat "activities" (game messages matching a rule from
 * {@link ActivityConfigService}) by enqueueing an attest event. Subscribes to {@link ChatMessage}.
 */
@Slf4j
@Singleton
public final class GameMessageCreditTracker
{
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

	/** Matches game/spam chat messages against configured activity rules and enqueues a credit attest on a hit. */
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

	/** Logs and chats a debug message for a matched activity rule, when debug chat is enabled. */
	private void debugActivityQueued(CompiledActivityConfig.CompiledChatRule matched)
	{
		if (!stateService.isDebugChatEnabled())
		{
			return;
		}

		String label = matched.getLabel();
		String what = label == null || label.isBlank() ? matched.getActivityId() : label;
		String body = String.format(
			"Activity \"%s\" -> +%s credits (total %s)",
			what,
			NumberFormatting.format(matched.getCredits()),
			NumberFormatting.format(stateService.getCredits()));
		log.info("[TCG DEBUG] {}", body);
		TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, body);
	}

	/** First configured chat rule whose pattern matches {@code messageWithoutTags}, if any. */
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

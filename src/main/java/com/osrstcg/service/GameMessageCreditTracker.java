package com.osrstcg.service;

import com.osrstcg.ui.TcgPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Awards credits for activities that cannot use {@link NpcKillCreditTracker} (e.g. raid NPCs with no combat level).
 */
@Singleton
public final class GameMessageCreditTracker
{
	private static final long CHAMBERS_OF_XERIC_CHALLENGE_MODE_COMPLETION_CREDITS = 18_500L;
	private static final String CHAMBERS_OF_XERIC_CHALLENGE_MODE_COMPLETION_PREFIX =
		"Your completed Chambers of Xeric Challenge Mode count is:";

	private static final long CHAMBERS_OF_XERIC_COMPLETION_CREDITS = 12_500L;
	private static final String CHAMBERS_OF_XERIC_COMPLETION_PREFIX = "Your completed Chambers of Xeric count is:";

	private static final long GAUNTLET_COMPLETION_CREDITS = 1_750L;
	private static final String GAUNTLET_COMPLETION_PREFIX = "Your Gauntlet completion count is:";

	private static final long CORRUPTED_GAUNTLET_COMPLETION_CREDITS = 4_500L;
	private static final String CORRUPTED_GAUNTLET_COMPLETION_PREFIX = "Your Corrupted Gauntlet completion count is:";

	private static final long ALCHEMICAL_HYDRA_KILL_CREDITS = 426L;
	private static final String ALCHEMICAL_HYDRA_KILL_PREFIX = "Your Alchemical Hydra kill count is:";

	private static final long GROTESQUE_GUARDIANS_KILL_CREDITS = 476L;
	private static final String GROTESQUE_GUARDIANS_KILL_PREFIX = "Your Grotesque Guardians kill count is:";

	private static final long HUEYCOATL_KILL_CREDITS = 642L;
	private static final String HUEYCOATL_KILL_PREFIX = "Your Hueycoatl kill count is:";

	private static final long ROYAL_TITANS_KILL_CREDITS = 525L;
	private static final String ROYAL_TITANS_KILL_PREFIX = "Your Royal Titans kill count is:";

	private static final long NIGHTMARE_KILL_CREDITS = 814L;
	private static final String NIGHTMARE_KILL_PREFIX = "Your Nightmare kill count is:";

	private static final long PHOSANIS_NIGHTMARE_KILL_CREDITS = 1_024L;
	private static final String PHOSANIS_NIGHTMARE_KILL_PREFIX = "Your Phosani's Nightmare kill count is:";

	private static final long PHANTOM_MUSPAH_KILL_CREDITS = 741L;
	private static final String PHANTOM_MUSPAH_KILL_PREFIX = "Your Phantom Muspah kill count is:";

	private static final long ABYSSAL_SIRE_KILL_CREDITS = 350L;
	private static final String ABYSSAL_SIRE_KILL_PREFIX = "Your Abyssal Sire kill count is:";

	private static final long SHELLBANE_GRYPHON_KILL_CREDITS = 235L;
	private static final String SHELLBANE_GRYPHON_KILL_PREFIX = "Your Shellbane Gryphon kill count is:";

	private static final List<CreditRule> CREDIT_RULES = buildCreditRules();

	private static List<CreditRule> buildCreditRules()
	{
		List<CreditRule> rules = new ArrayList<>();
		rules.add(CreditRule.prefix(
			CHAMBERS_OF_XERIC_CHALLENGE_MODE_COMPLETION_PREFIX,
			CHAMBERS_OF_XERIC_CHALLENGE_MODE_COMPLETION_CREDITS,
			"Chambers of Xeric Challenge Mode completion"));
		rules.add(CreditRule.prefix(
			CHAMBERS_OF_XERIC_COMPLETION_PREFIX,
			CHAMBERS_OF_XERIC_COMPLETION_CREDITS,
			"Chambers of Xeric completion"));
		rules.add(CreditRule.prefix(
			GAUNTLET_COMPLETION_PREFIX,
			GAUNTLET_COMPLETION_CREDITS,
			"The Gauntlet completion"));
		rules.add(CreditRule.prefix(
			CORRUPTED_GAUNTLET_COMPLETION_PREFIX,
			CORRUPTED_GAUNTLET_COMPLETION_CREDITS,
			"Corrupted Gauntlet completion"));
		rules.add(CreditRule.prefix(
			ALCHEMICAL_HYDRA_KILL_PREFIX,
			ALCHEMICAL_HYDRA_KILL_CREDITS,
			"Alchemical Hydra kill"));
		rules.add(CreditRule.prefix(
			HUEYCOATL_KILL_PREFIX,
			HUEYCOATL_KILL_CREDITS,
			"The Hueycoatl kill"));
		rules.add(CreditRule.prefix(
			GROTESQUE_GUARDIANS_KILL_PREFIX,
			GROTESQUE_GUARDIANS_KILL_CREDITS,
			"Grotesque Guardians kill"));
		rules.add(CreditRule.prefix(
			ROYAL_TITANS_KILL_PREFIX,
			ROYAL_TITANS_KILL_CREDITS,
			"Royal Titans kill"));
		rules.add(CreditRule.prefix(
			NIGHTMARE_KILL_PREFIX,
			NIGHTMARE_KILL_CREDITS,
			"The Nightmare kill"));
		rules.add(CreditRule.prefix(
			PHOSANIS_NIGHTMARE_KILL_PREFIX,
			PHOSANIS_NIGHTMARE_KILL_CREDITS,
			"Phosani's Nightmare kill"));
		rules.add(CreditRule.prefix(
			PHANTOM_MUSPAH_KILL_PREFIX,
			PHANTOM_MUSPAH_KILL_CREDITS,
			"Phantom Muspah kill"));
		rules.add(CreditRule.prefix(
			ABYSSAL_SIRE_KILL_PREFIX,
			ABYSSAL_SIRE_KILL_CREDITS,
			"Abyssal Sire kill"));
		rules.add(CreditRule.prefix(
			SHELLBANE_GRYPHON_KILL_PREFIX,
			SHELLBANE_GRYPHON_KILL_CREDITS,
			"Shellbane Gryphon kill"));
		return List.copyOf(rules);
	}

	private final CreditAwardService creditAwardService;
	private final TcgPanel tcgPanel;

	@Inject
	GameMessageCreditTracker(CreditAwardService creditAwardService, TcgPanel tcgPanel)
	{
		this.creditAwardService = creditAwardService;
		this.tcgPanel = tcgPanel;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event == null || !ChatMessageType.GAMEMESSAGE.equals(event.getType()))
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		Optional<CreditRule> rule = firstMatchingRule(message);
		if (rule.isEmpty())
		{
			return;
		}

		CreditRule matched = rule.get();
		creditAwardService.awardFlatCredits(matched.getReason(), matched.getCredits());
		tcgPanel.refresh();
	}

	private static Optional<CreditRule> firstMatchingRule(String messageWithoutTags)
	{
		for (CreditRule rule : CREDIT_RULES)
		{
			if (rule.matches(messageWithoutTags))
			{
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}

	private static final class CreditRule
	{
		private final String messagePrefix;
		private final long credits;
		private final String reason;

		private CreditRule(String messagePrefix, long credits, String reason)
		{
			this.messagePrefix = messagePrefix;
			this.credits = credits;
			this.reason = reason;
		}

		static CreditRule prefix(String messagePrefix, long credits, String reason)
		{
			return new CreditRule(messagePrefix, credits, reason);
		}

		boolean matches(String message)
		{
			return message != null && message.startsWith(messagePrefix);
		}

		long getCredits()
		{
			return credits;
		}

		String getReason()
		{
			return reason;
		}
	}
}

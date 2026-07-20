package com.osrstcg.service;

import com.osrstcg.ui.TcgPanel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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

	private static final long THEATRE_OF_BLOOD_HARD_MODE_COMPLETION_CREDITS = 18_500L;
	private static final String THEATRE_OF_BLOOD_HARD_MODE_COMPLETION_PREFIX =
		"Your completed Theatre of Blood: Hard Mode count is:";

	private static final long THEATRE_OF_BLOOD_ENTRY_MODE_COMPLETION_CREDITS = 3_500L;
	private static final String THEATRE_OF_BLOOD_ENTRY_MODE_COMPLETION_PREFIX =
		"Your completed Theatre of Blood: Entry Mode count is:";

	private static final long THEATRE_OF_BLOOD_COMPLETION_CREDITS = 12_500L;
	private static final String THEATRE_OF_BLOOD_COMPLETION_PREFIX = "Your completed Theatre of Blood count is:";

	private static final long TOMBS_OF_AMASCUT_EXPERT_MODE_COMPLETION_CREDITS = 18_500L;
	private static final String TOMBS_OF_AMASCUT_EXPERT_MODE_COMPLETION_PREFIX =
		"Your completed Tombs of Amascut: Expert Mode count is:";

	private static final long TOMBS_OF_AMASCUT_ENTRY_MODE_COMPLETION_CREDITS = 3_500L;
	private static final String TOMBS_OF_AMASCUT_ENTRY_MODE_COMPLETION_PREFIX =
		"Your completed Tombs of Amascut: Entry Mode count is:";

	private static final long TOMBS_OF_AMASCUT_COMPLETION_CREDITS = 12_500L;
	private static final String TOMBS_OF_AMASCUT_COMPLETION_PREFIX = "Your completed Tombs of Amascut count is:";

	private static final long GAUNTLET_COMPLETION_CREDITS = 1_750L;
	private static final String GAUNTLET_COMPLETION_PREFIX = "Your Gauntlet completion count is:";

	private static final long CORRUPTED_GAUNTLET_COMPLETION_CREDITS = 4_500L;
	private static final String CORRUPTED_GAUNTLET_COMPLETION_PREFIX = "Your Corrupted Gauntlet completion count is:";

	private static final long TZKAL_ZUK_KILL_CREDITS = 25_000L;
	private static final String TZKAL_ZUK_KILL_PREFIX = "Your TzKal-Zuk kill count is:";

	private static final long TZTOK_JAD_KILL_CREDITS = 10_000L;
	private static final String TZTOK_JAD_KILL_PREFIX = "Your TzTok-Jad kill count is:";

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

	private static final long BEGINNER_TREASURE_TRAIL_CREDITS = 500L;
	private static final long EASY_TREASURE_TRAIL_CREDITS = 1_000L;
	private static final long MEDIUM_TREASURE_TRAIL_CREDITS = 2_000L;
	private static final long HARD_TREASURE_TRAIL_CREDITS = 3_000L;
	private static final long ELITE_TREASURE_TRAIL_CREDITS = 4_000L;
	private static final long MASTER_TREASURE_TRAIL_CREDITS = 5_000L;

	/**
	 * Boss KC / completion lines use {@link ChatMessageType#GAMEMESSAGE} by default, but
	 * {@link ChatMessageType#SPAM} when the in-game "Filter out boss kill-count with spam-filter" setting is on.
	 */
	private static final Set<ChatMessageType> CREDIT_CHAT_TYPES = EnumSet.of(
		ChatMessageType.GAMEMESSAGE,
		ChatMessageType.SPAM);

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
			THEATRE_OF_BLOOD_HARD_MODE_COMPLETION_PREFIX,
			THEATRE_OF_BLOOD_HARD_MODE_COMPLETION_CREDITS,
			"Theatre of Blood Hard Mode completion"));
		rules.add(CreditRule.prefix(
			THEATRE_OF_BLOOD_ENTRY_MODE_COMPLETION_PREFIX,
			THEATRE_OF_BLOOD_ENTRY_MODE_COMPLETION_CREDITS,
			"Theatre of Blood Entry Mode completion"));
		rules.add(CreditRule.prefix(
			THEATRE_OF_BLOOD_COMPLETION_PREFIX,
			THEATRE_OF_BLOOD_COMPLETION_CREDITS,
			"Theatre of Blood completion"));
		rules.add(CreditRule.prefix(
			TOMBS_OF_AMASCUT_EXPERT_MODE_COMPLETION_PREFIX,
			TOMBS_OF_AMASCUT_EXPERT_MODE_COMPLETION_CREDITS,
			"Tombs of Amascut Expert Mode completion"));
		rules.add(CreditRule.prefix(
			TOMBS_OF_AMASCUT_ENTRY_MODE_COMPLETION_PREFIX,
			TOMBS_OF_AMASCUT_ENTRY_MODE_COMPLETION_CREDITS,
			"Tombs of Amascut Entry Mode completion"));
		rules.add(CreditRule.prefix(
			TOMBS_OF_AMASCUT_COMPLETION_PREFIX,
			TOMBS_OF_AMASCUT_COMPLETION_CREDITS,
			"Tombs of Amascut completion"));
		rules.add(CreditRule.prefix(
			GAUNTLET_COMPLETION_PREFIX,
			GAUNTLET_COMPLETION_CREDITS,
			"The Gauntlet completion"));
		rules.add(CreditRule.prefix(
			CORRUPTED_GAUNTLET_COMPLETION_PREFIX,
			CORRUPTED_GAUNTLET_COMPLETION_CREDITS,
			"Corrupted Gauntlet completion"));
		rules.add(CreditRule.prefix(
			TZKAL_ZUK_KILL_PREFIX,
			TZKAL_ZUK_KILL_CREDITS,
			"TzKal-Zuk kill"));
		rules.add(CreditRule.prefix(
			TZTOK_JAD_KILL_PREFIX,
			TZTOK_JAD_KILL_CREDITS,
			"TzTok-Jad kill"));
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
		rules.add(treasureTrailCompletion("beginner", BEGINNER_TREASURE_TRAIL_CREDITS));
		rules.add(treasureTrailCompletion("easy", EASY_TREASURE_TRAIL_CREDITS));
		rules.add(treasureTrailCompletion("medium", MEDIUM_TREASURE_TRAIL_CREDITS));
		rules.add(treasureTrailCompletion("hard", HARD_TREASURE_TRAIL_CREDITS));
		rules.add(treasureTrailCompletion("elite", ELITE_TREASURE_TRAIL_CREDITS));
		rules.add(treasureTrailCompletion("master", MASTER_TREASURE_TRAIL_CREDITS));
		return List.copyOf(rules);
	}

	/** Matches {@code You have completed N {difficulty} Treasure Trail(s).} */
	private static CreditRule treasureTrailCompletion(String difficulty, long credits)
	{
		String capitalized = Character.toUpperCase(difficulty.charAt(0)) + difficulty.substring(1);
		Pattern pattern = Pattern.compile(
			"^You have completed \\d+ " + Pattern.quote(difficulty) + " Treasure Trails?\\.$");
		return CreditRule.pattern(pattern, credits, capitalized + " Treasure Trail completion");
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
		if (event == null || !CREDIT_CHAT_TYPES.contains(event.getType()))
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
		private final Pattern messagePattern;
		private final long credits;
		private final String reason;

		private CreditRule(String messagePrefix, Pattern messagePattern, long credits, String reason)
		{
			this.messagePrefix = messagePrefix;
			this.messagePattern = messagePattern;
			this.credits = credits;
			this.reason = reason;
		}

		static CreditRule prefix(String messagePrefix, long credits, String reason)
		{
			return new CreditRule(messagePrefix, null, credits, reason);
		}

		static CreditRule pattern(Pattern messagePattern, long credits, String reason)
		{
			return new CreditRule(null, messagePattern, credits, reason);
		}

		boolean matches(String message)
		{
			if (message == null)
			{
				return false;
			}
			if (messagePattern != null)
			{
				return messagePattern.matcher(message).matches();
			}
			return message.startsWith(messagePrefix);
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

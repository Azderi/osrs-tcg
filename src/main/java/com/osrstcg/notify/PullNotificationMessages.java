package com.osrstcg.notify;

import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.ui.card.CardGrade;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
/**
 * Static helpers for building the text of pull/pack-summary notifications (chat, webhook, Dink) and
 * the small immutable value types those builders operate on.
 */
public final class PullNotificationMessages
{
	public static final String PLUGIN_TITLE = "OSRS TCG";

	private PullNotificationMessages()
	{
	}
/** One card pull, with the display/rarity data and notification eligibility needed to render it. */
	public static final class PackPull
	{
		public final String cardName;
		public final boolean newForCollection;
		public final boolean foil;
		public final RarityMath.Tier tier;
		public final String instanceId;
		public final boolean notificationEligible;
		public final Double condition;
/** Stores the pull's display/rarity data and notification eligibility verbatim. */
		public PackPull(
			String cardName,
			boolean newForCollection,
			boolean foil,
			RarityMath.Tier tier,
			String instanceId,
			boolean notificationEligible,
			Double condition)
		{
			this.cardName = cardName;
			this.newForCollection = newForCollection;
			this.foil = foil;
			this.tier = tier;
			this.instanceId = instanceId;
			this.notificationEligible = notificationEligible;
			this.condition = condition;
		}
	}
/** A pack's pulls split into new-cards and duplicates summary lines, ordered by foil status, tier, grade, and condition. */
	public static final class PackSummarySections
	{
		public final List<String> newCards;
		public final List<String> duplicates;
/** Stores the pre-built summary line lists verbatim. */
		PackSummarySections(List<String> newCards, List<String> duplicates)
		{
			this.newCards = newCards;
			this.duplicates = duplicates;
		}
	}
/** True if {@code value} is null, empty, or whitespace-only. */
	static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}
/** Trims a player name for display, falling back to a placeholder when blank/unknown. */
	static String playerLabel(String name)
	{
		return isBlank(name) ? "Unknown player" : name.trim();
	}
/** Builds the web "inspect this pull" URL for an instance id, or "" if the id is blank. */
	public static String inspectUrl(String instanceId)
	{
		if (instanceId == null || instanceId.isBlank())
		{
			return "";
		}
		return CloudEndpoints.webUrl("/inspect/" + instanceId.trim());
	}
	public static String collectionMessage(
		String playerName, String cardName, boolean newForCollection, boolean foil, String inspectUrl,
		Double condition)
	{
		String card = cardName == null ? "" : cardName.trim();
		String body = playerLabel(playerName) + " just added " + (newForCollection ? "" : "duplicate ") + card
			+ (foil ? " (foil)" : "") + CardGrade.gradeConditionSuffix(condition)
			+ " to their collection!";
		return appendInspectLink(body, inspectUrl);
	}
/** Appends a markdown "[Inspect card](url)" link when {@code inspectUrl} is non-blank; null-safe on message. */
	private static String appendInspectLink(String message, String inspectUrl)
	{
		if (message == null)
		{
			message = "";
		}
		if (inspectUrl == null || inspectUrl.isBlank())
		{
			return message;
		}
		return message + "\n[Inspect card](" + inspectUrl.trim() + ")";
	}
/** True if any pull in the list is flagged {@code notificationEligible}. */
	public static boolean hasEligiblePull(List<PackPull> pulls)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return false;
		}
		for (PackPull pull : pulls)
		{
			if (pull != null && pull.notificationEligible)
			{
				return true;
			}
		}
		return false;
	}
/**
	 * Returns the highest-tier notification-eligible foil pull (used as the summary thumbnail),
	 * falling back to the highest-tier eligible regular pull. Ties preserve pack order; pulls
	 * without a tier rank last within their foil/regular group. Null if there are no eligible pulls.
	 */
	public static PackPull highestTierPull(List<PackPull> pulls)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return null;
		}
		PackPull bestFoil = highestTierPull(pulls, true);
		return bestFoil == null ? highestTierPull(pulls, false) : bestFoil;
	}

/** Returns the highest-tier pull matching {@code foil}, retaining the first pull when tiers tie or are absent. */
	private static PackPull highestTierPull(List<PackPull> pulls, boolean foil)
	{
		PackPull best = null;
		for (PackPull pull : pulls)
		{
			if (pull == null || !pull.notificationEligible || pull.foil != foil)
			{
				continue;
			}
			if (best == null || tierRank(pull) > tierRank(best))
			{
				best = pull;
			}
		}
		return best;
	}
/** Renders a summary title with optional grade/condition, preserving its link and eligibility emphasis. */
	public static String summaryLine(PackPull pull, boolean showGradeAndCondition)
	{
		String displayName = pull.cardName.trim() + (pull.foil ? " (foil)" : "");
		String inspectUrl = inspectUrl(pull.instanceId);
		if (!inspectUrl.isEmpty())
		{
			displayName = "[" + displayName + "](" + inspectUrl + ")";
		}
		if (pull.notificationEligible)
		{
			displayName = "**" + displayName + "**";
		}
		return displayName + CardGrade.gradeConditionSuffix(showGradeAndCondition ? pull.condition : null);
	}
/**
 * Splits and sorts summary lines, putting foils before regular cards and ordering each group by
 * rarity tier (GODLY through COMMON), grade (S through E), then numeric condition (highest first).
 * Missing tiers, grades, and conditions rank last for their respective key; complete ties preserve
 * pack order.
 */
	public static PackSummarySections buildSummarySections(List<PackPull> pulls, boolean showGradeAndCondition)
	{
		List<String> newCards = new ArrayList<>();
		List<String> duplicates = new ArrayList<>();
		if (pulls == null || pulls.isEmpty())
		{
			return new PackSummarySections(newCards, duplicates);
		}
		List<PackPull> sorted = new ArrayList<>(pulls);
		sorted.sort(Comparator
			.comparing((PackPull pull) -> pull != null && pull.foil).reversed()
			.thenComparing(Comparator.comparingInt(PullNotificationMessages::tierRank).reversed())
			.thenComparingInt(PullNotificationMessages::gradeRank)
			.thenComparing(PullNotificationMessages::compareConditionDescending));
		for (PackPull pull : sorted)
		{
			if (pull == null || pull.cardName == null || pull.cardName.trim().isEmpty())
			{
				continue;
			}
			(pull.newForCollection ? newCards : duplicates).add(summaryLine(pull, showGradeAndCondition));
		}
		return new PackSummarySections(newCards, duplicates);
	}
/** Builds the "X opened a booster pack!" message with New cards / Duplicates sections appended. */
	public static String packSummaryMessage(String opener, PackSummarySections sections)
	{
		StringBuilder message = new StringBuilder(playerLabel(opener)).append(" opened a booster pack!");
		if (sections != null)
		{
			appendCardSection(message, "New cards", sections.newCards);
			appendCardSection(message, "Duplicates", sections.duplicates);
		}
		return message.toString();
	}
/** Sort key for a pull by rarity tier ordinal; -1 (lowest) when the pull or tier is missing. */
	private static int tierRank(PackPull pull)
	{
		return pull == null || pull.tier == null ? -1 : pull.tier.ordinal();
	}
/** Sort key for a pull by card grade (S through E); missing or invalid conditions sort last. */
	private static int gradeRank(PackPull pull)
	{
		CardGrade grade = pull == null ? null : CardGrade.gradeFromCondition(pull.condition);
		return grade == null ? Integer.MAX_VALUE : grade.ordinal();
	}
/** Compares valid numeric conditions highest-first; missing, NaN, and infinite values sort last. */
	private static int compareConditionDescending(PackPull left, PackPull right)
	{
		Double leftCondition = left == null ? null : left.condition;
		Double rightCondition = right == null ? null : right.condition;
		boolean leftValid = isValidCondition(leftCondition);
		boolean rightValid = isValidCondition(rightCondition);
		if (leftValid != rightValid)
		{
			return leftValid ? -1 : 1;
		}
		return leftValid ? Double.compare(rightCondition, leftCondition) : 0;
	}
/** True when a condition is present and finite. */
	private static boolean isValidCondition(Double condition)
	{
		return condition != null && !condition.isNaN() && !condition.isInfinite();
	}
/** Appends a "**heading**" section with a bulleted line per card, skipping blank entries; no-op if the list is empty. */
	private static void appendCardSection(StringBuilder message, String heading, List<String> cards)
	{
		if (cards == null || cards.isEmpty())
		{
			return;
		}
		message.append("\n\n**").append(heading).append("**");
		for (String card : cards)
		{
			if (card == null || card.trim().isEmpty())
			{
				continue;
			}
			message.append("\n- ").append(card);
		}
	}
}

package com.osrstcg.notify;

import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
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

		/** Stores the pull's display/rarity data and notification eligibility verbatim. */
		public PackPull(
			String cardName,
			boolean newForCollection,
			boolean foil,
			RarityMath.Tier tier,
			String instanceId,
			boolean notificationEligible)
		{
			this.cardName = cardName;
			this.newForCollection = newForCollection;
			this.foil = foil;
			this.tier = tier;
			this.instanceId = instanceId;
			this.notificationEligible = notificationEligible;
		}
	}

	/** A pack's pulls split into new-cards and duplicates summary lines, ordered by rarity. */
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

	/** Builds the "X just added Y to their collection" message, with an inspect link appended when available. */
	public static String collectionMessage(
		String playerName, String cardName, boolean newForCollection, boolean foil, String inspectUrl)
	{
		String card = cardName == null ? "" : cardName.trim();
		String body = playerLabel(playerName) + " just added " + (newForCollection ? "" : "duplicate ") + card
			+ (foil ? " (foil)" : "") + " to their collection!";
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
	 * Returns the pull with the highest rarity tier (used as the summary thumbnail), or the first
	 * pull if none have a tier. Null if the list is empty.
	 */
	public static PackPull highestTierPull(List<PackPull> pulls)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return null;
		}
		PackPull best = null;
		for (PackPull pull : pulls)
		{
			if (pull == null || pull.tier == null)
			{
				continue;
			}
			if (best == null || pull.tier.ordinal() > best.tier.ordinal())
			{
				best = pull;
			}
		}
		return best == null ? pulls.get(0) : best;
	}

	/** Renders one pull's summary bullet: card name (bolded if eligible), foil marker, and inspect link. */
	public static String summaryLine(PackPull pull)
	{
		String displayName = pull.cardName.trim() + (pull.foil ? " (foil)" : "");
		if (pull.notificationEligible)
		{
			displayName = "**" + displayName + "**";
		}
		String inspectUrl = inspectUrl(pull.instanceId);
		if (!inspectUrl.isEmpty())
		{
			displayName = displayName + " — [Inspect](" + inspectUrl + ")";
		}
		return displayName;
	}

	/** Splits pulls into new-cards/duplicates summary lines, sorted highest-tier first within each group. */
	public static PackSummarySections buildSummarySections(List<PackPull> pulls)
	{
		List<String> newCards = new ArrayList<>();
		List<String> duplicates = new ArrayList<>();
		if (pulls == null || pulls.isEmpty())
		{
			return new PackSummarySections(newCards, duplicates);
		}
		List<PackPull> sorted = new ArrayList<>(pulls);
		sorted.sort(Comparator.comparingInt(PullNotificationMessages::tierRank).reversed());
		for (PackPull pull : sorted)
		{
			if (pull == null || pull.cardName == null || pull.cardName.trim().isEmpty())
			{
				continue;
			}
			(pull.newForCollection ? newCards : duplicates).add(summaryLine(pull));
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

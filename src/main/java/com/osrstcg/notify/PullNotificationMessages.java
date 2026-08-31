package com.osrstcg.notify;

import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PullNotificationMessages
{
	public static final String PLUGIN_TITLE = "OSRS TCG";

	private PullNotificationMessages()
	{
	}

	public static final class PackPull
	{
		public final String cardName;
		public final boolean newForCollection;
		public final boolean foil;
		public final RarityMath.Tier tier;
		public final String instanceId;
		public final boolean notificationEligible;

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

	public static final class PackSummarySections
	{
		public final List<String> newCards;
		public final List<String> duplicates;

		PackSummarySections(List<String> newCards, List<String> duplicates)
		{
			this.newCards = newCards;
			this.duplicates = duplicates;
		}
	}

	public static String inspectUrl(String instanceId)
	{
		if (instanceId == null || instanceId.isBlank())
		{
			return "";
		}
		return CloudEndpoints.webUrl("/inspect/" + instanceId.trim());
	}

	public static String collectionMessage(
		String playerName, String cardName, boolean newForCollection, boolean foil, String inspectUrl)
	{
		String who = playerName == null || playerName.trim().isEmpty() ? "Unknown player" : playerName.trim();
		String card = cardName == null ? "" : cardName.trim();
		String body = who + " just added " + (newForCollection ? "" : "duplicate ") + card
			+ (foil ? " (foil)" : "") + " to their collection!";
		return appendInspectLink(body, inspectUrl);
	}

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

	public static String packSummaryMessage(String opener, PackSummarySections sections)
	{
		String who = opener == null || opener.trim().isEmpty() ? "Unknown player" : opener.trim();
		StringBuilder message = new StringBuilder(who).append(" opened a booster pack!");
		if (sections != null)
		{
			appendCardSection(message, "New cards", sections.newCards);
			appendCardSection(message, "Duplicates", sections.duplicates);
		}
		return message.toString();
	}

	private static int tierRank(PackPull pull)
	{
		return pull == null || pull.tier == null ? -1 : pull.tier.ordinal();
	}

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

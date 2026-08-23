package com.osrstcg.notify;

import com.osrstcg.cloud.api.CloudEndpoints;
import java.util.List;

public final class PullNotificationMessages
{
	private PullNotificationMessages()
	{
	}

	/** Absolute inspect URL for a pack-open card UUID, or empty when missing. */
	public static String inspectUrl(String instanceId)
	{
		if (instanceId == null || instanceId.isBlank())
		{
			return "";
		}
		return CloudEndpoints.webUrl("/inspect/" + instanceId.trim());
	}

	public static String collectionMessage(String playerName, String cardName, boolean newForCollection, boolean foil)
	{
		return collectionMessage(playerName, cardName, newForCollection, foil, null);
	}

	public static String collectionMessage(
		String playerName, String cardName, boolean newForCollection, boolean foil, String inspectUrl)
	{
		String who = playerName == null || playerName.trim().isEmpty() ? "Unknown player" : playerName.trim();
		String card = cardName == null ? "" : cardName.trim();
		String duplicatePrefix = newForCollection ? "" : "duplicate ";
		String foilSuffix = foil ? " (foil)" : "";
		String body = who + " just added " + duplicatePrefix + card + foilSuffix + " to their collection!";
		return appendInspectLink(body, inspectUrl);
	}

	public static String dinkCollectionMessage(String cardName, boolean newForCollection, boolean foil)
	{
		return dinkCollectionMessage(cardName, newForCollection, foil, null);
	}

	public static String dinkCollectionMessage(
		String cardName, boolean newForCollection, boolean foil, String inspectUrl)
	{
		return collectionMessage("%USERNAME%", cardName, newForCollection, foil, inspectUrl);
	}

	/** Append a Discord markdown inspect link when {@code inspectUrl} is non-blank. */
	public static String appendInspectLink(String message, String inspectUrl)
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

	public static String dinkPackSummaryMessage(List<String> newCards, List<String> duplicates)
	{
		StringBuilder message = new StringBuilder("%USERNAME% opened a booster pack!");
		appendCardSection(message, "New cards", newCards);
		appendCardSection(message, "Duplicates", duplicates);
		return message.toString();
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

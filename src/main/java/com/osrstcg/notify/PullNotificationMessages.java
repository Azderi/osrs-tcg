package com.osrstcg.notify;

import com.osrstcg.cloud.api.CloudEndpoints;
import java.util.List;

public final class PullNotificationMessages
{
	private PullNotificationMessages()
	{
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

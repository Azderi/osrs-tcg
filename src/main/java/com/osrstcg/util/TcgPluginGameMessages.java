package com.osrstcg.util;

import java.awt.Color;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * RuneLite chat markup for OSRS TCG game messages: gold {@code OSRS TCG} inside brackets; card names use rarity colours.
 */
public final class TcgPluginGameMessages
{
	/**
	 * Warm gold for the {@code OSRS TCG} label in game chat.
	 */
	public static final Color DEFAULT_PREFIX_COLOR = new Color(0xC4, 0x94, 0x1A);

	public static Color PREFIX_COLOR = DEFAULT_PREFIX_COLOR;

	/**
	 * Warm gold for Godly-tier card names in game chat.
	 */
	public static final Color CHAT_EMPHASIS_GOLD = new Color(0xC4, 0x94, 0x1A);

	private TcgPluginGameMessages()
	{
	}

	private static ChatMessageBuilder prefixBuilder()
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[")
			.append(PREFIX_COLOR, "OSRS TCG")
			.append(ChatColorType.NORMAL)
			.append("] ");
	}

	public static void setPrefixColor(Color color)
	{
		PREFIX_COLOR = color == null ? DEFAULT_PREFIX_COLOR : color;
	}

	/**
	 * {@code [} default {@code OSRS TCG]} gold {@code ]} default, then body. {@code body} is escaped as plain chat text.
	 */
	public static String withPrefix(String body)
	{
		if (body == null)
		{
			body = "";
		}
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(body)
			.build();
	}

	/**
	 * Card name for pull / collection lines: trimmed name, {@code (foil)} suffix when applicable, no quotes.
	 */
	public static String announcedCardLabel(String cardName, boolean foil)
	{
		String n = cardName == null ? "" : cardName.trim();
		if (n.isEmpty())
		{
			n = "Unknown card";
		}
		return foil ? n + " (foil)" : n;
	}

	public static String formatPrefixedSomeonePulled(String who, String cardName, boolean foil, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(who)
			.append(ChatColorType.NORMAL)
			.append(" just pulled ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append("!")
			.build();
	}

	public static String plainPrefixedSomeonePulled(String who, String cardName, boolean foil)
	{
		return "[OSRS TCG] " + who + " just pulled " + announcedCardLabel(cardName, foil) + "!";
	}

	public static String formatPrefixedSomeoneAddedCollection(String who, String cardName, boolean foil, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(who)
			.append(ChatColorType.NORMAL)
			.append(" just added ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" to their collection!")
			.build();
	}

	public static String plainPrefixedSomeoneAddedCollection(String who, String cardName, boolean foil)
	{
		return "[OSRS TCG] " + who + " just added " + announcedCardLabel(cardName, foil) + " to their collection!";
	}

	public static String formatPrefixedYouPulled(String cardName, boolean foil, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append("You just pulled ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append("!")
			.build();
	}

	public static String plainPrefixedYouPulled(String cardName, boolean foil)
	{
		return "[OSRS TCG] You just pulled " + announcedCardLabel(cardName, foil) + "!";
	}

	public static String formatPrefixedYouAddedCollection(String cardName, boolean foil, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append("You just added ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" to your collection!")
			.build();
	}

	public static String plainPrefixedYouAddedCollection(String cardName, boolean foil)
	{
		return "[OSRS TCG] You just added " + announcedCardLabel(cardName, foil) + " to your collection!";
	}

	public static String formatPrefixedSomeoneSentYou(String who, String cardName, boolean foil, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(who)
			.append(ChatColorType.NORMAL)
			.append(" just sent you ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" !")
			.build();
	}

	public static String plainPrefixedSomeoneSentYou(String who, String cardName, boolean foil)
	{
		return "[OSRS TCG] " + who + " just sent you " + announcedCardLabel(cardName, foil) + " !";
	}

	public static String formatPrefixedYouSentCard(String cardName, boolean foil, String target, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append("Sent ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" to ")
			.append(ChatColorType.NORMAL)
			.append(target)
			.append(ChatColorType.NORMAL)
			.append(".")
			.build();
	}

	public static String plainPrefixedYouSentCard(String cardName, boolean foil, String target)
	{
		return "[OSRS TCG] Sent " + announcedCardLabel(cardName, foil) + " to " + target + ".";
	}

	public static void queueFormattedGameMessage(ChatMessageManager chatMessageManager, String formatted, String plain)
	{
		if (chatMessageManager == null)
		{
			return;
		}
		if (formatted == null)
		{
			formatted = "";
		}
		if (plain == null)
		{
			plain = "";
		}
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted)
			.value(plain)
			.build());
	}

	/**
	 * Queues a game message with RuneLite colour markup applied (see {@link ChatMessageManager}).
	 */
	public static void queuePrefixedGameMessage(ChatMessageManager chatMessageManager, String body)
	{
		if (body == null)
		{
			body = "";
		}
		queueFormattedGameMessage(chatMessageManager, withPrefix(body), "[OSRS TCG] " + body);
	}

	public static String formatPrefixedNotEnoughCredits(String action)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(" Not enough credits to ")
			.append(action)
			.append(".")
			.build();
	}
}

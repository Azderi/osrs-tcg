package com.runelitetcg.util;

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
	/** Warm gold for the {@code OSRS TCG} label (brackets stay default game colour). */
	private static final Color PLUGIN_BRAND_GOLD = new Color(0xFF, 0xC4, 0x2A);

	private TcgPluginGameMessages()
	{
	}

	private static ChatMessageBuilder goldPluginPrefixBuilder()
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[")
			.append(PLUGIN_BRAND_GOLD, "OSRS TCG")
			.append(ChatColorType.NORMAL)
			.append("] ");
	}

	/**
	 * {@code [} default {@code OSRS TCG]} gold {@code ]} default, then body. {@code body} is escaped as plain chat text.
	 */
	public static String withGoldPluginPrefix(String body)
	{
		if (body == null)
		{
			body = "";
		}
		return goldPluginPrefixBuilder()
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

	public static String formatGoldPrefixedSomeonePulled(String who, String cardName, boolean foil, Color rarityColor)
	{
		return goldPluginPrefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(who)
			.append(ChatColorType.NORMAL)
			.append(" just pulled ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append("!")
			.build();
	}

	public static String plainGoldPrefixedSomeonePulled(String who, String cardName, boolean foil)
	{
		return "[OSRS TCG] " + who + " just pulled " + announcedCardLabel(cardName, foil) + "!";
	}

	public static String formatGoldPrefixedSomeoneAddedCollection(String who, String cardName, boolean foil, Color rarityColor)
	{
		return goldPluginPrefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(who)
			.append(ChatColorType.NORMAL)
			.append(" just added ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" to their collection!")
			.build();
	}

	public static String plainGoldPrefixedSomeoneAddedCollection(String who, String cardName, boolean foil)
	{
		return "[OSRS TCG] " + who + " just added " + announcedCardLabel(cardName, foil) + " to their collection!";
	}

	public static String formatGoldPrefixedYouPulled(String cardName, boolean foil, Color rarityColor)
	{
		return goldPluginPrefixBuilder()
			.append(ChatColorType.NORMAL)
			.append("You just pulled ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append("!")
			.build();
	}

	public static String plainGoldPrefixedYouPulled(String cardName, boolean foil)
	{
		return "[OSRS TCG] You just pulled " + announcedCardLabel(cardName, foil) + "!";
	}

	public static String formatGoldPrefixedYouAddedCollection(String cardName, boolean foil, Color rarityColor)
	{
		return goldPluginPrefixBuilder()
			.append(ChatColorType.NORMAL)
			.append("You just added ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" to your collection!")
			.build();
	}

	public static String plainGoldPrefixedYouAddedCollection(String cardName, boolean foil)
	{
		return "[OSRS TCG] You just added " + announcedCardLabel(cardName, foil) + " to your collection!";
	}

	public static String formatGoldPrefixedSomeoneSentYou(String who, String cardName, boolean foil, Color rarityColor)
	{
		return goldPluginPrefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(who)
			.append(ChatColorType.NORMAL)
			.append(" just sent you ")
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" !")
			.build();
	}

	public static String plainGoldPrefixedSomeoneSentYou(String who, String cardName, boolean foil)
	{
		return "[OSRS TCG] " + who + " just sent you " + announcedCardLabel(cardName, foil) + " !";
	}

	public static String formatGoldPrefixedYouSentCard(String cardName, boolean foil, String target, Color rarityColor)
	{
		return goldPluginPrefixBuilder()
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

	public static String plainGoldPrefixedYouSentCard(String cardName, boolean foil, String target)
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
	public static void queueGoldPluginGameMessage(ChatMessageManager chatMessageManager, String body)
	{
		if (body == null)
		{
			body = "";
		}
		queueFormattedGameMessage(chatMessageManager, withGoldPluginPrefix(body), "[OSRS TCG] " + body);
	}
}

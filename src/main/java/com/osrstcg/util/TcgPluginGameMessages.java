package com.osrstcg.util;

import java.awt.Color;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

public final class TcgPluginGameMessages
{
	public static final Color DEFAULT_PREFIX_COLOR = new Color(0xC4, 0x94, 0x1A);

	public static Color PREFIX_COLOR = DEFAULT_PREFIX_COLOR;

	public static final Color CHAT_EMPHASIS_GOLD = new Color(0xC4, 0x94, 0x1A);

	private static final String PLAIN_PREFIX = "[OSRS TCG] ";
	private static final String PLAIN_DEBUG_PREFIX = "[TCG DEBUG] ";

	private TcgPluginGameMessages()
	{
	}

	public static String plainPrefix()
	{
		return PLAIN_PREFIX;
	}

	public static ChatMessageBuilder prefixBuilder()
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[")
			.append(PREFIX_COLOR, "OSRS TCG")
			.append(ChatColorType.NORMAL)
			.append("] ");
	}

	private static ChatMessageBuilder debugPrefixBuilder()
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[")
			.append(PREFIX_COLOR, "TCG DEBUG")
			.append(ChatColorType.NORMAL)
			.append("] ");
	}

	public static void setPrefixColor(Color color)
	{
		PREFIX_COLOR = color == null ? DEFAULT_PREFIX_COLOR : color;
	}

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

	public static String withDebugPrefix(String body)
	{
		if (body == null)
		{
			body = "";
		}
		return debugPrefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(body)
			.build();
	}

	public static String stripLeadingPluginPrefix(String message)
	{
		if (message == null || message.isEmpty())
		{
			return "";
		}
		if (message.startsWith(PLAIN_PREFIX))
		{
			return message.substring(PLAIN_PREFIX.length());
		}
		if (message.startsWith("[OSRS TCG]"))
		{
			return message.substring("[OSRS TCG]".length()).replaceFirst("^\\s+", "");
		}
		if (message.startsWith(PLAIN_DEBUG_PREFIX))
		{
			return message.substring(PLAIN_DEBUG_PREFIX.length());
		}
		if (message.startsWith("[TCG DEBUG]"))
		{
			return message.substring("[TCG DEBUG]".length()).replaceFirst("^\\s+", "");
		}
		return message;
	}

	public static String announcedCardLabel(String cardName, boolean foil)
	{
		String n = cardName == null ? "" : cardName.trim();
		if (n.isEmpty())
		{
			n = "Unknown card";
		}
		return foil ? n + " (foil)" : n;
	}

	public static String formatPrefixedSomeoneAddedCollection(
		String who, String cardName, boolean newForCollection, boolean foil, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append(who)
			.append(ChatColorType.NORMAL)
			.append(" just added ")
			.append(ChatColorType.NORMAL)
			.append(duplicatePrefix(newForCollection))
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" to their collection!")
			.build();
	}

	public static String plainPrefixedSomeoneAddedCollection(
		String who, String cardName, boolean newForCollection, boolean foil)
	{
		return PLAIN_PREFIX + who + " just added " + duplicatePrefix(newForCollection)
			+ announcedCardLabel(cardName, foil) + " to their collection!";
	}

	public static String formatPrefixedYouAddedCollection(
		String cardName, boolean newForCollection, boolean foil, Color rarityColor)
	{
		return prefixBuilder()
			.append(ChatColorType.NORMAL)
			.append("You just added ")
			.append(ChatColorType.NORMAL)
			.append(duplicatePrefix(newForCollection))
			.append(rarityColor, announcedCardLabel(cardName, foil))
			.append(ChatColorType.NORMAL)
			.append(" to your collection!")
			.build();
	}

	public static String plainPrefixedYouAddedCollection(String cardName, boolean newForCollection, boolean foil)
	{
		return PLAIN_PREFIX + "You just added " + duplicatePrefix(newForCollection)
			+ announcedCardLabel(cardName, foil) + " to your collection!";
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
		boolean hasFormattedTag = formatted.contains("OSRS TCG") || formatted.contains("TCG DEBUG");
		boolean hasPlainPrefix = plain.startsWith(PLAIN_PREFIX) || plain.startsWith("[OSRS TCG]")
			|| plain.startsWith(PLAIN_DEBUG_PREFIX) || plain.startsWith("[TCG DEBUG]");
		if (!hasFormattedTag || !hasPlainPrefix)
		{
			String body = stripLeadingPluginPrefix(plain);
			if (body.isEmpty() && !formatted.isEmpty())
			{
				body = stripLeadingFormattedPluginPrefix(formatted).replaceAll("(?i)</?col[^>]*>", "");
			}
			boolean debug = plain.startsWith(PLAIN_DEBUG_PREFIX) || plain.startsWith("[TCG DEBUG]")
				|| formatted.contains("TCG DEBUG");
			if (debug)
			{
				formatted = withDebugPrefix(body);
				plain = PLAIN_DEBUG_PREFIX + body;
			}
			else if (!hasFormattedTag && formatted.contains("<col"))
			{
				formatted = prefixBuilder().build() + stripLeadingFormattedPluginPrefix(formatted);
				plain = PLAIN_PREFIX + stripLeadingPluginPrefix(plain.isEmpty() ? body : plain);
			}
			else
			{
				formatted = withPrefix(body);
				plain = PLAIN_PREFIX + body;
			}
		}
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted)
			.value(plain)
			.build());
	}

	static String stripLeadingFormattedPluginPrefix(String formatted)
	{
		if (formatted == null || formatted.isEmpty())
		{
			return "";
		}
		String s = formatted;
		s = s.replaceFirst("^\\[(?:<col=[0-9A-Fa-f]{6}>)?OSRS TCG(?:</col>)?]\\s*", "");
		s = s.replaceFirst("^\\[(?:<col=[0-9A-Fa-f]{6}>)?TCG DEBUG(?:</col>)?]\\s*", "");
		if (s.startsWith(PLAIN_PREFIX))
		{
			s = s.substring(PLAIN_PREFIX.length());
		}
		else if (s.startsWith(PLAIN_DEBUG_PREFIX))
		{
			s = s.substring(PLAIN_DEBUG_PREFIX.length());
		}
		return s;
	}

	public static void queuePrefixedGameMessage(ChatMessageManager chatMessageManager, String body)
	{
		if (body == null)
		{
			body = "";
		}
		body = stripLeadingPluginPrefix(body);
		queueFormattedGameMessage(chatMessageManager, withPrefix(body), PLAIN_PREFIX + body);
	}

	public static void queueDebugGameMessage(ChatMessageManager chatMessageManager, String body)
	{
		if (body == null)
		{
			body = "";
		}
		body = stripLeadingPluginPrefix(body);
		queueFormattedGameMessage(chatMessageManager, withDebugPrefix(body), PLAIN_DEBUG_PREFIX + body);
	}

	public static void queueGameMessage(ChatMessageManager chatMessageManager, String message)
	{
		queuePrefixedGameMessage(chatMessageManager, message);
	}

	private static String duplicatePrefix(boolean newForCollection)
	{
		return newForCollection ? "" : "duplicate ";
	}
}

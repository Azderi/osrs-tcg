package com.osrstcg.util;

import java.awt.Color;
import net.runelite.api.ChatMessageType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
/**
 * Builds and queues the plugin's game-message chat lines: the "[OSRS TCG]"/"[TCG DEBUG]" prefixes,
 * collection-pickup announcements, and de-duplication of already-prefixed text before re-queueing.
 */
public final class TcgPluginGameMessages
{
/** Default gold color for the "OSRS TCG"/"TCG DEBUG" bracket text. */
	public static final Color DEFAULT_PREFIX_COLOR = new Color(0xC4, 0x94, 0x1A);
/** Current color used for the "OSRS TCG"/"TCG DEBUG" bracket text; configurable via {@link #setPrefixColor}. */
	public static Color PREFIX_COLOR = DEFAULT_PREFIX_COLOR;

	private static final String PLAIN_PREFIX = "[OSRS TCG] ";
	private static final String PLAIN_DEBUG_PREFIX = "[TCG DEBUG] ";
/** No instances. */
	private TcgPluginGameMessages()
	{
	}
/** @return the plain-text (non-colored) message prefix, e.g. for the {@code value} field of a {@link QueuedMessage}. */
	public static String plainPrefix()
	{
		return PLAIN_PREFIX;
	}
/** @return a new {@link ChatMessageBuilder} pre-loaded with the colored "[OSRS TCG] " prefix. */
	public static ChatMessageBuilder prefixBuilder()
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[")
			.append(PREFIX_COLOR, "OSRS TCG")
			.append(ChatColorType.NORMAL)
			.append("] ");
	}
/** @return a new {@link ChatMessageBuilder} pre-loaded with the colored "[TCG DEBUG] " prefix. */
	private static ChatMessageBuilder debugPrefixBuilder()
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[")
			.append(PREFIX_COLOR, "TCG DEBUG")
			.append(ChatColorType.NORMAL)
			.append("] ");
	}
/** Sets {@link #PREFIX_COLOR}, falling back to {@link #DEFAULT_PREFIX_COLOR} if {@code color} is {@code null}. */
	public static void setPrefixColor(Color color)
	{
		PREFIX_COLOR = color == null ? DEFAULT_PREFIX_COLOR : color;
	}
/** @return {@code body} (empty if {@code null}) rendered with the colored "OSRS TCG" prefix, as a RuneLite-formatted string. */
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
/** @return {@code body} (empty if {@code null}) rendered with the colored "TCG DEBUG" prefix, as a RuneLite-formatted string. */
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
/** @return {@code message} with a leading plain "[OSRS TCG] "/"[TCG DEBUG]" prefix (with or without trailing space) removed; {@code ""} for null/empty input; unchanged if no prefix matches. */
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
/** @return {@code cardName} (or "Unknown card" if blank), with " (foil)" appended when {@code foil} is true. */
	public static String announcedCardLabel(String cardName, boolean foil)
	{
		String n = cardName == null ? "" : cardName.trim();
		if (n.isEmpty())
		{
			n = "Unknown card";
		}
		return foil ? n + " (foil)" : n;
	}
/** @return colored, RuneLite-formatted "{who} just added [duplicate] {card} to their collection!" message. */
	public static String formatSomeoneAddedCollection(
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
/** @return plain-text version of {@link #formatSomeoneAddedCollection}, for the {@link QueuedMessage} value field. */
	public static String plainSomeoneAddedCollection(
		String who, String cardName, boolean newForCollection, boolean foil)
	{
		return PLAIN_PREFIX + who + " just added " + duplicatePrefix(newForCollection)
			+ announcedCardLabel(cardName, foil) + " to their collection!";
	}
/** @return colored, RuneLite-formatted "You just added [duplicate] {card} to your collection!" message. */
	public static String formatYouAddedCollection(
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
/** @return plain-text version of {@link #formatYouAddedCollection}, for the {@link QueuedMessage} value field. */
	public static String plainYouAddedCollection(String cardName, boolean newForCollection, boolean foil)
	{
		return PLAIN_PREFIX + "You just added " + duplicatePrefix(newForCollection)
			+ announcedCardLabel(cardName, foil) + " to your collection!";
	}
/**
	 * Queues a game message on {@code chatMessageManager}, ensuring both the formatted and plain
	 * text carry a plugin prefix (normal or debug) before they're sent. If either string is missing
	 * its expected prefix, strips any partial/malformed prefix from the body and re-applies a clean
	 * one, matching debug-vs-normal styling from whichever of {@code formatted}/{@code plain}
	 * indicates it. No-op if {@code chatMessageManager} is {@code null}.
	 */
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
			String body = stripFormattedPluginPrefix(plain);
			if (body.isEmpty() && !formatted.isEmpty())
			{
				body = stripFormattedPluginPrefix(formatted).replaceAll("(?i)</?col[^>]*>", "");
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
				formatted = prefixBuilder().build() + stripFormattedPluginPrefix(formatted);
				plain = PLAIN_PREFIX + stripFormattedPluginPrefix(plain.isEmpty() ? body : plain);
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
/** @return {@code formatted} with a leading (optionally {@code <col=...>}-wrapped) "OSRS TCG"/"TCG DEBUG" bracket tag, or a plain prefix, stripped; {@code ""} for null/empty input. */
	static String stripFormattedPluginPrefix(String formatted)
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
/** Queues {@code body} (any existing plugin prefix stripped first) as a normal, prefixed game message. */
	public static void queuePrefixedGameMessage(ChatMessageManager chatMessageManager, String body)
	{
		if (body == null)
		{
			body = "";
		}
		body = stripLeadingPluginPrefix(body);
		queueFormattedGameMessage(chatMessageManager, withPrefix(body), PLAIN_PREFIX + body);
	}
/** Schedules {@link #queuePrefixedGameMessage} to run on the client thread. No-op if {@code clientThread} or {@code body} is null, or {@code body} is empty. */
	public static void queueOnClientThread(ClientThread clientThread, ChatMessageManager chatMessageManager, String body)
	{
		if (clientThread == null || body == null || body.isEmpty())
		{
			return;
		}
		clientThread.invokeLater(() -> queuePrefixedGameMessage(chatMessageManager, body));
	}
/** Queues {@code body} (any existing plugin prefix stripped first) as a debug-prefixed game message. */
	public static void queueDebugGameMessage(ChatMessageManager chatMessageManager, String body)
	{
		if (body == null)
		{
			body = "";
		}
		body = stripLeadingPluginPrefix(body);
		queueFormattedGameMessage(chatMessageManager, withDebugPrefix(body), PLAIN_DEBUG_PREFIX + body);
	}
/** @return {@code "duplicate "} when the card isn't new for the collection, else {@code ""}. */
	private static String duplicatePrefix(boolean newForCollection)
	{
		return newForCollection ? "" : "duplicate ";
	}
}

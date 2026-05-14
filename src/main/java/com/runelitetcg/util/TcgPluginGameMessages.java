package com.runelitetcg.util;

import java.awt.Color;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * RuneLite chat markup for OSRS TCG game messages: gold {@code OSRS TCG} inside brackets, rest default color.
 */
public final class TcgPluginGameMessages
{
	/** Warm gold for the {@code OSRS TCG} label (brackets stay default game colour). */
	private static final Color PLUGIN_BRAND_GOLD = new Color(0xFF, 0xC4, 0x2A);

	private TcgPluginGameMessages()
	{
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
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[")
			.append(PLUGIN_BRAND_GOLD, "OSRS TCG")
			.append(ChatColorType.NORMAL)
			.append("] ")
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

	/**
	 * Queues a game message with RuneLite colour markup applied (see {@link ChatMessageManager}).
	 */
	public static void queueGoldPluginGameMessage(ChatMessageManager chatMessageManager, String body)
	{
		if (chatMessageManager == null)
		{
			return;
		}
		if (body == null)
		{
			body = "";
		}
		String formatted = withGoldPluginPrefix(body);
		String plain = "[OSRS TCG] " + body;
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted)
			.value(plain)
			.build());
	}
}

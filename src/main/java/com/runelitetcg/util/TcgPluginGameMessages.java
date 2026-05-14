package com.runelitetcg.util;

import java.awt.Color;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;

/**
 * RuneLite chat markup for OSRS TCG game messages (gold brand prefix).
 */
public final class TcgPluginGameMessages
{
	/** Warm gold for the {@code [OSRS TCG]} tag in game chat. */
	private static final Color PLUGIN_BRAND_GOLD = new Color(0xFF, 0xC4, 0x2A);

	private TcgPluginGameMessages()
	{
	}

	/**
	 * Prefix {@code [OSRS TCG]} in gold, then default game text. {@code body} is escaped as plain chat text.
	 */
	public static String withGoldPluginPrefix(String body)
	{
		if (body == null)
		{
			body = "";
		}
		return new ChatMessageBuilder()
			.append(PLUGIN_BRAND_GOLD, "[OSRS TCG]")
			.append(ChatColorType.NORMAL)
			.append(" ")
			.append(body)
			.build();
	}
}

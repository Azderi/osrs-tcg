package com.osrstcg;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("osrstcg")
public interface OsrsTcgConfig extends Config
{
	@ConfigItem(
		keyName = "enableSounds",
		name = "Enable sounds",
		description = "Play custom plugin sounds for interfaces."
	)
	default boolean enableSounds()
	{
		return true;
	}

	@ConfigItem(
		keyName = "partyAnnounceMythicPulls",
		name = "Party collection announcements",
		description = "When in a RuneLite party, show chat announcements for rare pulls and collection status."
	)
	default boolean partyAnnounceMythicPulls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableFileBackups",
		name = "Backups",
		description = "Keep up to 50 file backups under .runelite/OSRS-TCG/backups. "
			+ "Written on logout, plugin load/unload, and ::tcg-save. "
			+ "Used automatically when profile configuration saves fail to load."
	)
	default boolean enableFileBackups()
	{
		return true;
	}

	@ConfigItem(
		keyName = "safeMode",
		name = "Safe-mode",
		description = "Block opening booster packs while in combat. If a pack reveal is open when combat starts, close it immediately and list pulled cards in chat (cards remain in your collection)."
	)
	default boolean safeMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chatPrefixColor",
		name = "Chat prefix colour",
		description = "Choose the colour used for the [OSRS TCG] chat prefix. "
			+ "Written on plugin load/unload"
	)
	default Color chatPrefixColor()
	{
		return new Color(0xC4, 0x94, 0x1A);
	}
}

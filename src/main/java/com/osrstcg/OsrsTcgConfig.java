package com.runelitetcg;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runelitetcg")
public interface RuneLiteTcgConfig extends Config
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
		keyName = "safeMode",
		name = "Safe-mode",
		description = "Block opening booster packs while in combat. If a pack reveal is open when combat starts, close it immediately and list pulled cards in chat (cards remain in your collection)."
	)
	default boolean safeMode()
	{
		return false;
	}
}

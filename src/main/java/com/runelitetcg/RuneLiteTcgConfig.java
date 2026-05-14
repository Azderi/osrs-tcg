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
		description = "Play pack reveal sounds (Godly hum while a face-down Godly-tier card remains, deal motion, card flip, Godly reveal chime) when supported."
	)
	default boolean enableSounds()
	{
		return false;
	}

	@ConfigItem(
		keyName = "partyAnnounceMythicPulls",
		name = "Party collection announcements",
		description = "When in a RuneLite party, send and show chat for Godly-tier pack pulls (new vs duplicate wording) and when someone completes a primary-category set (roll pool). Requires Party plugin / party session."
	)
	default boolean partyAnnounceMythicPulls()
	{
		return true;
	}
}

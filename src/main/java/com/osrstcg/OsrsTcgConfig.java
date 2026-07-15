package com.osrstcg;

import com.osrstcg.model.PullNotifyTier;
import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrstcg")
public interface OsrsTcgConfig extends Config
{
	@ConfigSection(
		name = "Pull notifications",
		description = "In-game chat and optional Dink webhook notifications for notable pack pulls.",
		position = 0
	)
	String pullNotificationsSection = "pullNotifications";

	@ConfigItem(
		keyName = "notifyTier",
		name = "Notify tier",
		description = "Send notifications for cards of this rarity tier and higher.",
		section = pullNotificationsSection,
		position = 0
	)
	default PullNotifyTier notifyTier()
	{
		return PullNotifyTier.MYTHIC;
	}

	@ConfigItem(
		keyName = "notifyNonFoils",
		name = "Notify non-foils",
		description = "Send notifications for non-foil cards that meet the selected notify tier.",
		section = pullNotificationsSection,
		position = 1
	)
	default boolean notifyNonFoils()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyFoils",
		name = "Notify all foils",
		description = "Also send notifications when you pull a foil card below the selected notify tier.",
		section = pullNotificationsSection,
		position = 2
	)
	default boolean notifyFoils()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyNewCardsOnly",
		name = "Only notify new cards",
		description = "Only send pull notifications for cards that are new to your collection.",
		section = pullNotificationsSection,
		position = 3
	)
	default boolean notifyNewCardsOnly()
	{
		return true;
	}

	@ConfigItem(
		keyName = "partyAnnounceMythicPulls",
		name = "Party collection announcements",
		description = "When in a RuneLite party, sync notable pull notifications to party members. "
			+ "Uses the same notify tier, foil, and new-card rules as chat notifications.",
		section = pullNotificationsSection,
		position = 4
	)
	default boolean partyAnnounceMythicPulls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dinkNotifications",
		name = "Dink",
		description = "When a pull triggers a chat notification, also send it to Discord via the Dink plugin "
			+ "(requires Dink's External Plugin Notifications). Includes a screenshot of the client.",
		section = pullNotificationsSection,
		position = 5
	)
	default boolean dinkNotifications()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pullWebhookUrl",
		name = "Webhook URL",
		description = "Optional Discord webhook URL(s) for pull notifications. "
			+ "You can target multiple webhooks by specifying their URLs on separate lines. "
			+ "Sends a rarity-coloured embed with card artwork when a pull triggers a chat notification. "
			+ "Leave empty to disable.",
		section = pullNotificationsSection,
		position = 6
	)
	default String pullWebhookUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "enableSounds",
		name = "Enable pack opening sounds",
		description = "Play custom plugin sounds during pack openings (deal, flip, reveal, hum, and apex hover).",
		position = 10
	)
	default boolean enableSounds()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableTransferSound",
		name = "Enable transfer sound",
		description = "Play a sound when a party card transfer completes.",
		position = 11
	)
	default boolean enableTransferSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "packRarityHighlight",
		name = "Rarity Highlight",
		description = "Peek at the rarity of the card when hovering over an unflipped card during pack openings.",
		position = 12
	)
	default boolean packRarityHighlight()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enable3dCards",
		name = "3D cards",
		description = "Tilt cards toward the mouse during pack openings.",
		position = 13
	)
	default boolean enable3dCards()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableFileBackups",
		name = "Backups",
		description = "Keep up to 50 file backups under .runelite/OSRS-TCG/backups. "
			+ "Written on logout, plugin load/unload, and ::tcg-save. "
			+ "Used automatically when profile configuration saves fail to load.",
		position = 14
	)
	default boolean enableFileBackups()
	{
		return true;
	}

	@ConfigItem(
		keyName = "safeMode",
		name = "Safe-mode",
		description = "Block opening booster packs while in combat. If a pack reveal is open when combat starts, close it immediately and list pulled cards in chat (cards remain in your collection).",
		position = 19
	)
	default boolean safeMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chatPrefixColor",
		name = "Chat prefix colour",
		description = "Choose the colour used for the [OSRS TCG] chat prefix. "
			+ "Written on plugin load/unload",
		position = 20
	)
	default Color chatPrefixColor()
	{
		return new Color(0xC4, 0x94, 0x1A);
	}

	@ConfigItem(
		keyName = "debugMessages",
		name = "Debug messages",
		description = "Show detailed credit-award lines in game chat.",
		position = 21
	)
	default boolean debugMessages()
	{
		return false;
	}
}

package com.osrstcg;

import com.osrstcg.config.CreditsPerHourWindow;
import com.osrstcg.config.DinkNotificationTrigger;
import com.osrstcg.config.PullNotifyTier;
import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrstcg")
public interface OsrsTcgConfig extends Config
{
	@ConfigSection(
		name = "General",
		description = "General plugin settings.",
		position = 0
	)
	String generalSection = "general";

	@ConfigItem(
		keyName = "creditsInfobox",
		name = "Credits infobox",
		description = "Show your credits on screen. Alt+drag to move. Shift+right-click to open packs "
			+ "or reset Credits/h.",
		section = generalSection,
		position = 0
	)
	default boolean creditsInfobox()
	{
		return false;
	}

	@ConfigItem(
		keyName = "creditsPerHour",
		name = "Credits per hour",
		description = "Show credits/h on the credits infobox. Shift+right-click the infobox to reset.",
		section = generalSection,
		position = 1
	)
	default boolean creditsPerHour()
	{
		return true;
	}

	@ConfigItem(
		keyName = "creditsPerHourWindow",
		name = "Credits/h window",
		description = "Sliding window for credits/h. Persistent keeps all gains until Shift+right-click "
			+ "Reset on the credits infobox.",
		section = generalSection,
		position = 2
	)
	default CreditsPerHourWindow creditsPerHourWindow()
	{
		return CreditsPerHourWindow.PERSISTENT;
	}

	@ConfigItem(
		keyName = "shopNotifications",
		name = "Shop notifications",
		description = "Chat when you can afford a booster pack.",
		section = generalSection,
		position = 3
	)
	default boolean shopNotifications()
	{
		return true;
	
	
	}@ConfigItem(
		keyName = "runeliteNotifications",
		name = "Runelite notifications",
		description = "Enable certain notifications to be sent through Runelite's default notification service as well.",
		section = generalSection,
		position = 4
	)
	default boolean runeliteNotifications()
	{
		return false;
	}

	@ConfigItem(
		keyName = "compactShop",
		name = "Compact shop",
		description = "Hide pack thumbnails in the shop so more packs fit on the sidebar.",
		section = generalSection,
		position = 5
	)
	default boolean compactShop()
	{
		return false;
	}

	@ConfigItem(
		keyName = "enableSounds",
		name = "Enable pack opening sounds",
		description = "Play sounds when opening packs.",
		section = generalSection,
		position = 6
	)
	default boolean enableSounds()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGradeWear",
		name = "Show grade wear",
		description = "Show condition wear effects on cards in the pack opening overlay.",
		section = generalSection,
		position = 7
	)
	default boolean showGradeWear()
	{
		return true;
	}

	@ConfigItem(
		keyName = "packRarityHighlight",
		name = "Rarity Highlight",
		description = "Show rarity when hovering unflipped pack cards.",
		section = generalSection,
		position = 8
	)
	default boolean packRarityHighlight()
	{
		return true;
	}

	@ConfigItem(
		keyName = "packRarityText",
		name = "Rarity Text",
		description = "Show the rarity name above unflipped pack cards on hover. Helps colour blind users "
			+ "tell rarities apart without relying on the highlight colour.",
		section = generalSection,
		position = 9
	)
	default boolean packRarityText()
	{
		return false;
	}

	@ConfigItem(
		keyName = "safeMode",
		name = "Safe-mode",
		description = "Block opening packs while in combat.",
		section = generalSection,
		position = 10
	)
	default boolean safeMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSidebarRanks",
		name = "Sidebar hiscores ranks",
		description = "Show your hiscores rank under overview stats after opening a pack "
			+ "(updated at most once every 10 minutes).",
		section = generalSection,
		position = 11
	)
	default boolean showSidebarRanks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatPrefixColor",
		name = "Chat prefix colour",
		description = "Colour of the [OSRS TCG] chat tag.",
		section = generalSection,
		position = 12
	)
	default Color chatPrefixColor()
	{
		return new Color(0xC4, 0x94, 0x1A);
	}

	@ConfigItem(
		keyName = "debugMessages",
		name = "Debug messages",
		description = "Show extra plugin details in chat.",
		section = generalSection,
		position = 13
	)
	default boolean debugMessages()
	{
		return false;
	}

	@ConfigSection(
		name = "Pull notifications",
		description = "Alerts for notable pack pulls.",
		position = 10
	)
	String pullNotificationsSection = "pullNotifications";

	@ConfigItem(
		keyName = "notifyTier",
		name = "Notify tier",
		description = "Notify for this rarity and higher.",
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
		description = "Also notify for normal (non-foil) cards.",
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
		description = "Notify for every foil pull.",
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
		description = "Only notify when the card is new to you.",
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
		description = "Share pull alerts with your party.",
		section = pullNotificationsSection,
		position = 4
	)
	default boolean partyAnnounceMythicPulls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pullWebhookUrl",
		name = "Webhook URL",
		description = "Discord webhook for pull alerts. Leave empty to disable.",
		section = pullNotificationsSection,
		position = 5
	)
	default String pullWebhookUrl()
	{
		return "";
	}

	@ConfigSection(
		name = "Dink",
		description = "Send OSRS TCG notifications through Dink.",
		position = 20
	)
	String dinkSection = "dink";

	@ConfigItem(
		keyName = "dinkNotifications",
		name = "Enable Dink Notifications",
		description = "Send notable pull alerts to Discord via Dink.",
		section = dinkSection,
		position = 0
	)
	default boolean dinkNotifications()
	{
		return false;
	}

	@ConfigItem(
		keyName = "dinkNotificationTrigger",
		name = "Trigger notification",
		description = "Send Dink notifications as each card is revealed or after the whole pack is revealed.",
		section = dinkSection,
		position = 1
	)
	default DinkNotificationTrigger dinkNotificationTrigger()
	{
		return DinkNotificationTrigger.EVERY_CARD;
	}

	@ConfigItem(
		keyName = "dinkNewCardNotifyTier",
		name = "Notify tier",
		description = "Notify for this rarity and higher.",
		section = dinkSection,
		position = 2
	)
	default PullNotifyTier dinkNewCardNotifyTier()
	{
		return PullNotifyTier.MYTHIC;
	}

	@ConfigItem(
		keyName = "dinkAlwaysNotifyFoils",
		name = "Notify all foils",
		description = "Notify for foils regardless of rank. When disabled, foils must meet the relevant rank threshold.",
		section = dinkSection,
		position = 3
	)
	default boolean dinkAlwaysNotifyFoils()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dinkOnlyNotifyNew",
		name = "Only notify new cards",
		description = "Only send Dink notifications for new cards at or above the selected rank threshold.",
		section = dinkSection,
		position = 4
	)
	default boolean dinkOnlyNotifyNew()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dinkDuplicateNotifyTier",
		name = "Duplicate notify tier",
		description = "Minimum card rank for duplicate Dink notifications if only notify new cards is turned off.",
		section = dinkSection,
		position = 5
	)
	default PullNotifyTier dinkDuplicateNotifyTier()
	{
		return PullNotifyTier.MYTHIC;
	}
}

package com.osrstcg;

import com.osrstcg.config.CreditsPerHourWindow;
import com.osrstcg.config.PullNotificationTrigger;
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

	@ConfigSection(
		name = "Credits",
		description = "Credits display and notifications.",
		position = 5
	)
	String creditsSection = "credits";

	@ConfigItem(
		keyName = "creditsInfobox",
		name = "Credits infobox",
		description = "Show your credits on screen. Alt+drag to move. Shift+right-click to open packs "
			+ "or reset Credits/h.",
		section = creditsSection,
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
		section = creditsSection,
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
		section = creditsSection,
		position = 2
	)
	default CreditsPerHourWindow creditsPerHourWindow()
	{
		return CreditsPerHourWindow.PERSISTENT;
	}

	@ConfigItem(
		keyName = "creditNotifications",
		name = "Credit notifications",
		description = "Chat when you have the amount of credits you set.",
		section = creditsSection,
		position = 3
	)
	default boolean creditNotifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "creditNotificationAmount",
		name = "Notification amount",
		description = "Credit threshold for notifications.",
		section = creditsSection,
		position = 4
	)
	default int creditNotificationAmount()
	{
		return 2500;
	}

	@ConfigItem(
		keyName = "runeliteNotifications",
		name = "RuneLite notifications",
		description = "Also send credit notifications through RuneLite's notification service.",
		section = creditsSection,
		position = 5
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
		position = 0
	)
	default boolean compactShop()
	{
		return false;
	}

	@ConfigSection(
		name = "Pack opening",
		description = "Pack reveal overlay and sounds.",
		position = 7
	)
	String packOpeningSection = "packOpening";

	@ConfigItem(
		keyName = "enableSounds",
		name = "Enable pack opening sounds",
		description = "Play sounds when opening packs.",
		section = packOpeningSection,
		position = 0
	)
	default boolean enableSounds()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGradeWear",
		name = "Show grade wear",
		description = "Show condition wear effects on cards in the pack opening overlay.",
		section = packOpeningSection,
		position = 1
	)
	default boolean showGradeWear()
	{
		return true;
	}

	@ConfigItem(
		keyName = "packRarityHighlight",
		name = "Rarity Highlight",
		description = "Show rarity when hovering unflipped pack cards.",
		section = packOpeningSection,
		position = 2
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
		section = packOpeningSection,
		position = 3
	)
	default boolean packRarityText()
	{
		return false;
	}

	@ConfigItem(
		keyName = "ignoreBetaForNewStatus",
		name = "Ignore beta for new status",
		description = "Beta copies do not count as owned when deciding if a pull is new.",
		section = packOpeningSection,
		position = 4
	)
	default boolean ignoreBetaForNewStatus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSidebarRanks",
		name = "Sidebar hiscores ranks",
		description = "Show your hiscores rank under overview stats after opening a pack.",
		section = generalSection,
		position = 1
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
		position = 2
	)
	default Color chatPrefixColor()
	{
		return new Color(0xC4, 0x94, 0x1A);
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
		keyName = "duplicateNotifyTier",
		name = "Duplicate notify tier",
		description = "Minimum rarity for duplicate pulls.",
		section = pullNotificationsSection,
		position = 1
	)
	default PullNotifyTier duplicateNotifyTier()
	{
		return PullNotifyTier.LEGENDARY;
	}

	@ConfigItem(
		keyName = "notifyNonFoils",
		name = "Notify non-foils",
		description = "Also notify for normal cards.",
		section = pullNotificationsSection,
		position = 2
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
		position = 3
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
		position = 4
	)
	default boolean notifyNewCardsOnly()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pullNotificationTrigger",
		name = "Notification trigger",
		description = "Notify per card or one summary at pack end.",
		section = pullNotificationsSection,
		position = 5
	)
	default PullNotificationTrigger pullNotificationTrigger()
	{
		return PullNotificationTrigger.EVERY_CARD;
	}

	@ConfigItem(
		keyName = "partyAnnouncePulls",
		name = "Party/chat announcements",
		description = "Post alerts to game chat and share them with party members.",
		section = pullNotificationsSection,
		position = 6
	)
	default boolean partyAnnouncePulls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pullWebhookUrl",
		name = "Webhook URL",
		description = "Discord webhook URL for pull alerts.",
		section = pullNotificationsSection,
		position = 7
	)
	default String pullWebhookUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "dinkNotifications",
		name = "Enable Dink notifications",
		description = "Send pull alerts via Dink.",
		section = pullNotificationsSection,
		position = 8
	)
	default boolean dinkNotifications()
	{
		return false;
	}

	@ConfigSection(
		name = "Debug",
		description = "Developer and troubleshooting options.",
		position = 15
	)
	String debugSection = "debug";

	@ConfigItem(
		keyName = "debugMessages",
		name = "Debug messages",
		description = "Chat debug messages",
		section = debugSection,
		position = 0
	)
	default boolean debugMessages()
	{
		return false;
	}
}

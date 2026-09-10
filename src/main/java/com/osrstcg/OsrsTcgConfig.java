package com.osrstcg;

import com.osrstcg.config.CreditsPerHourWindow;
import com.osrstcg.config.PullNotificationTrigger;
import com.osrstcg.config.PullNotifyTier;
import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
/**
 * RuneLite config schema for OSRS TCG. Each default method is a config item; the value shown to
 * the player is driven by the {@code name}/{@code description} on the {@link ConfigItem}
 * annotation, and the default method's return value is the out-of-the-box default.
 */
@ConfigGroup("osrstcg")
public interface OsrsTcgConfig extends Config
{
	@ConfigSection(
		name = "General",
		description = "General settings.",
		position = 0
	)
	String generalSection = "general";

	@ConfigSection(
		name = "Credits",
		description = "Credits display/notifications.",
		position = 5
	)
	String creditsSection = "credits";
/** Whether to show the on-screen credits infobox. */
	@ConfigItem(
		keyName = "creditsInfobox",
		name = "Credits infobox",
		description = "On-screen credits.",
		section = creditsSection,
		position = 0
	)
	default boolean creditsInfobox()
	{
		return false;
	}
/** Whether the credits infobox also shows a credits/hour rate. */
	@ConfigItem(
		keyName = "creditsPerHour",
		name = "Credits per hour",
		description = "Credits/h; Shift+RMB reset.",
		section = creditsSection,
		position = 1
	)
	default boolean creditsPerHour()
	{
		return true;
	}
/** Sliding window used to compute the credits/hour rate shown on the infobox. */
	@ConfigItem(
		keyName = "creditsPerHourWindow",
		name = "Credits/h window",
		description = "Credits/h window.",
		section = creditsSection,
		position = 2
	)
	default CreditsPerHourWindow creditsPerHourWindow()
	{
		return CreditsPerHourWindow.PERSISTENT;
	}
/** Whether to post a chat message when the credits threshold in {@link #creditNotificationAmount()} is reached. */
	@ConfigItem(
		keyName = "creditNotifications",
		name = "Credit notifications",
		description = "Chat at credit threshold.",
		section = creditsSection,
		position = 3
	)
	default boolean creditNotifications()
	{
		return true;
	}
/** Credit balance threshold that triggers {@link #creditNotifications()}. */
	@ConfigItem(
		keyName = "creditNotificationAmount",
		name = "Notification amount",
		description = "Credit notify threshold.",
		section = creditsSection,
		position = 4
	)
	default int creditNotificationAmount()
	{
		return 2500;
	}
/** Whether credit notifications are also routed through RuneLite's OS-level notification service. */
	@ConfigItem(
		keyName = "runeliteNotifications",
		name = "RuneLite notifications",
		description = "Also notify via RuneLite notifications.",
		section = creditsSection,
		position = 5
	)
	default boolean runeliteNotifications()
	{
		return false;
	}

	@ConfigSection(
		name = "Pack opening",
		description = "Pack overlay and sounds.",
		position = 7
	)
	String packOpeningSection = "packOpening";
/** Whether pack-opening sound effects play. */
	@ConfigItem(
		keyName = "enableSounds",
		name = "Enable pack opening sounds",
		description = "Play pack-opening sounds.",
		section = packOpeningSection,
		position = 0
	)
	default boolean enableSounds()
	{
		return true;
	}
/** Whether condition/wear visual effects render on cards in the pack reveal overlay. */
	@ConfigItem(
		keyName = "showGradeWear",
		name = "Show grade wear",
		description = "Wear effects on pack-opening cards.",
		section = packOpeningSection,
		position = 1
	)
	default boolean showGradeWear()
	{
		return true;
	}
/** Whether hovering an unflipped pack card highlights it by rarity. */
	@ConfigItem(
		keyName = "packRarityHighlight",
		name = "Rarity Highlight",
		description = "Rarity highlight on unflipped pack cards.",
		section = packOpeningSection,
		position = 2
	)
	default boolean packRarityHighlight()
	{
		return true;
	}
/** Whether hovering an unflipped pack card shows its rarity name as text (accessibility aid alongside the highlight color). */
	@ConfigItem(
		keyName = "packRarityText",
		name = "Rarity Text",
		description = "Rarity name on hover for unflipped pack cards.",
		section = packOpeningSection,
		position = 3
	)
	default boolean packRarityText()
	{
		return false;
	}
/** Whether beta-variant copies are excluded when deciding if a pulled card counts as "new". */
	@ConfigItem(
		keyName = "ignoreBetaForNewStatus",
		name = "Ignore beta for new status",
		description = "Beta copies ignored for new-card status.",
		section = packOpeningSection,
		position = 4
	)
	default boolean ignoreBetaForNewStatus()
	{
		return true;
	}
/** Whether the sidebar shows the player's OSRS hiscores rank under overview stats after opening a pack. */
	@ConfigItem(
		keyName = "showSidebarRanks",
		name = "Sidebar hiscores ranks",
		description = "Hiscores rank under overview after pack open.",
		section = generalSection,
		position = 1
	)
	default boolean showSidebarRanks()
	{
		return true;
	}
/** Color of the "[OSRS TCG]" chat message prefix. */
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
/** Whether chat message right-click menus include a TCG trade request option. */
	@ConfigItem(
		keyName = "friendsMenuOption",
		name = "Friends menu option",
		description = "TCG trade request on friend right-click menus.",
		section = generalSection,
		position = 3
	)
	default boolean friendsMenuOption()
	{
		return true;
	}

	@ConfigSection(
		name = "Pull notifications",
		description = "Pack pull alerts.",
		position = 10
	)
	String pullNotificationsSection = "pullNotifications";
/** Minimum rarity tier that triggers a pull notification. */
	@ConfigItem(
		keyName = "notifyTier",
		name = "Notify tier",
		description = "Minimum rarity to notify.",
		section = pullNotificationsSection,
		position = 0
	)
	default PullNotifyTier notifyTier()
	{
		return PullNotifyTier.MYTHIC;
	}
/** Minimum rarity tier that triggers a notification when the pull is a duplicate. */
	@ConfigItem(
		keyName = "duplicateNotifyTier",
		name = "Duplicate notify tier",
		description = "Min rarity for duplicates.",
		section = pullNotificationsSection,
		position = 1
	)
	default PullNotifyTier duplicateNotifyTier()
	{
		return PullNotifyTier.LEGENDARY;
	}
/** Whether non-foil (normal) card pulls can trigger notifications. */
	@ConfigItem(
		keyName = "notifyNonFoils",
		name = "Notify non-foils",
		description = "Notify for normal cards.",
		section = pullNotificationsSection,
		position = 2
	)
	default boolean notifyNonFoils()
	{
		return true;
	}
/** Whether every foil card pull triggers a notification, regardless of rarity tier. */
	@ConfigItem(
		keyName = "notifyFoils",
		name = "Notify all foils",
		description = "Notify every foil pull.",
		section = pullNotificationsSection,
		position = 3
	)
	default boolean notifyFoils()
	{
		return true;
	}
/** Whether pull notifications are limited to cards new to the player's collection. */
	@ConfigItem(
		keyName = "notifyNewCardsOnly",
		name = "Only notify new cards",
		description = "Only notify new cards.",
		section = pullNotificationsSection,
		position = 4
	)
	default boolean notifyNewCardsOnly()
	{
		return true;
	}
/** Whether pull notifications include grade and condition. */
	@ConfigItem(
		keyName = "showPullGradeAndCondition",
		name = "Show grade and condition",
		description = "Grade/condition in pull alerts.",
		section = pullNotificationsSection,
		position = 5
	)
	default boolean showPullGradeAndCondition()
	{
		return true;
	}
/** Whether pull notifications fire per card as the pack is revealed, or as one summary at pack end. */
	@ConfigItem(
		keyName = "pullNotificationTrigger",
		name = "Notification trigger",
		description = "Per card or pack-end summary.",
		section = pullNotificationsSection,
		position = 6
	)
	default PullNotificationTrigger pullNotificationTrigger()
	{
		return PullNotificationTrigger.EVERY_CARD;
	}
/** Whether pull alerts are also posted to game chat and shared with party members. */
	@ConfigItem(
		keyName = "partyAnnouncePulls",
		name = "Party/chat announcements",
		description = "Game chat and party share.",
		section = pullNotificationsSection,
		position = 7
	)
	default boolean partyAnnouncePulls()
	{
		return true;
	}
/** Discord webhook URL that pull alerts are posted to; empty disables webhook posting. */
	@ConfigItem(
		keyName = "pullWebhookUrl",
		name = "Webhook URL",
		description = "Discord webhook URL(s).",
		section = pullNotificationsSection,
		position = 8
	)
	default String pullWebhookUrl()
	{
		return "";
	}
/** Whether pull alerts are also sent through the Dink plugin's notification channel. */
	@ConfigItem(
		keyName = "dinkNotifications",
		name = "Enable Dink notifications",
		description = "Send via Dink.",
		section = pullNotificationsSection,
		position = 9
	)
	default boolean dinkNotifications()
	{
		return false;
	}

	@ConfigSection(
		name = "Debug",
		description = "Debug options.",
		position = 15
	)
	String debugSection = "debug";
/** Whether verbose debug messages are printed to chat. */
	@ConfigItem(
		keyName = "debugMessages",
		name = "Debug messages",
		description = "Chat debug.",
		section = debugSection,
		position = 0
	)
	default boolean debugMessages()
	{
		return false;
	}
}

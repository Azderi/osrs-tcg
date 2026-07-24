package com.osrstcg;

import com.osrstcg.model.DinkNotificationTrigger;
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
		name = "General",
		description = "General plugin settings.",
		position = 0
	)
	String generalSection = "general";

	@ConfigItem(
		keyName = "creditsInfobox",
		name = "Credits infobox",
		description = "Show your credits on screen. Alt+drag to move. Shift+right-click to open packs.",
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
		description = "Show credits/h on the credits infobox.",
		section = generalSection,
		position = 1
	)
	default boolean creditsPerHour()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shopNotifications",
		name = "Shop notifications",
		description = "Chat when you can afford a booster pack.",
		section = generalSection,
		position = 2
	)
	default boolean shopNotifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "runeliteNotifications",
		name = "Runelite notifications",
		description = "Enable certain notifications to be sent through Runelite's default notification service as well.",
		section = generalSection,
		position = 3
	)
	default boolean runeliteNotifications()
	{
		return false;
	}

	@ConfigItem(
		keyName = "enableSounds",
		name = "Enable pack opening sounds",
		description = "Play sounds when opening packs.",
		section = generalSection,
		position = 4
	)
	default boolean enableSounds()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableTransferSound",
		name = "Enable transfer sound",
		description = "Play a sound when a card trade finishes.",
		section = generalSection,
		position = 5
	)
	default boolean enableTransferSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "packRarityHighlight",
		name = "Rarity Highlight",
		description = "Show rarity when hovering unflipped pack cards.",
		section = generalSection,
		position = 6
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
		position = 7
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
		position = 8
	)
	default boolean safeMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chatPrefixColor",
		name = "Chat prefix colour",
		description = "Colour of the [OSRS TCG] chat tag.",
		section = generalSection,
		position = 9
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
		position = 10
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

	@ConfigSection(
		name = "Web album",
		description = "Share your collection online via osrs-tcg.xyz.",
		position = 30
	)
	String webAlbumSection = "webAlbum";

	String WEB_SHARE_ENABLED_WARNING =
		"Enabling this uploads your OSRS TCG collection, collection statistics and\n"
			+ "IP address to a third-party server not controlled or verified by RuneLite developers.\n\n"
			+ "Your collection will be publicly viewable under your display name and\n"
			+ "will remain visible for a period even after you disable this feature.";

	@ConfigItem(
		keyName = "webShareEnabled",
		name = "Share collection online",
		description = "Show your collection on osrs-tcg.xyz while logged in.",
		section = webAlbumSection,
		position = 0,
		warning = WEB_SHARE_ENABLED_WARNING
	)
	default boolean webShareEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "webShareApiKey",
		name = "API key",
		description = "Key needed to share your collection online.",
		section = webAlbumSection,
		position = 1,
		secret = true
	)
	default String webShareApiKey()
	{
		return "";
	}
}

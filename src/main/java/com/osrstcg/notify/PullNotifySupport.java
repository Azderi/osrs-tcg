package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.config.PullNotificationTrigger;
import com.osrstcg.config.PullNotifyTier;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.pack.PackRevealService.RevealCard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Shared logic for building notification content and deciding eligibility, used by chat, party,
 * webhook, and Dink notifiers so they stay in sync on filtering rules and message text.
 */
@Singleton
public class PullNotifySupport
{
	/** Pre-built end-of-pack summary: sections, thumbnail image, and rarity tier for the embed color. */
	public static final class PackSummaryContent
	{
		public final PullNotificationMessages.PackSummarySections sections;
		public final String imageUrl;
		public final RarityMath.Tier tier;

		/** Stores the summary sections, thumbnail image URL, and tier verbatim. */
		PackSummaryContent(PullNotificationMessages.PackSummarySections sections, String imageUrl, RarityMath.Tier tier)
		{
			this.sections = sections;
			this.imageUrl = imageUrl;
			this.tier = tier;
		}

		/** Renders the summary message with the given opener name substituted in. */
		public String messageFor(String opener)
		{
			return PullNotificationMessages.packSummaryMessage(opener, sections);
		}
	}

	/** Pre-built per-card notification content: message text, card image URL, and inspect link. */
	public static final class PullCardContent
	{
		public final String description;
		public final String imageUrl;
		public final String inspectUrl;

		/** Stores the description, image URL, and inspect URL verbatim. */
		PullCardContent(String description, String imageUrl, String inspectUrl)
		{
			this.description = description;
			this.imageUrl = imageUrl;
			this.inspectUrl = inspectUrl;
		}
	}

	private final OsrsTcgConfig config;
	private final CardDatabase cardDatabase;
	private final TcgPublicStatsCalculator tcgPublicStatsCalculator;
	private final TcgChatStatsShareService tcgChatStatsShareService;

	/** Wires config, the card database, and the public-stats calculator/share service used to build stats lines. */
	@Inject
	PullNotifySupport(
		OsrsTcgConfig config,
		CardDatabase cardDatabase,
		TcgPublicStatsCalculator tcgPublicStatsCalculator,
		TcgChatStatsShareService tcgChatStatsShareService)
	{
		this.config = config;
		this.cardDatabase = cardDatabase;
		this.tcgPublicStatsCalculator = tcgPublicStatsCalculator;
		this.tcgChatStatsShareService = tcgChatStatsShareService;
	}

	/**
	 * Decides whether a pull is eligible for external notification (webhook/Dink), applying the
	 * new-cards-only, foil, non-foil, and per-category tier-floor config settings.
	 */
	public boolean shouldNotify(RarityMath.Tier tier, boolean foil, boolean newForCollection)
	{
		PullNotifyTier floor = newForCollection ? config.notifyTier() : config.duplicateNotifyTier();
		if (config.notifyNewCardsOnly() && !newForCollection && !(foil && config.notifyFoils()))
		{
			return false;
		}
		if (foil)
		{
			if (tier == null)
			{
				return config.notifyFoils();
			}
			return meetsTier(tier, floor) || config.notifyFoils();
		}
		if (tier == null || !config.notifyNonFoils())
		{
			return false;
		}
		return meetsTier(tier, floor);
	}

	/** Returns the configured notification trigger, defaulting to per-card when unset. */
	public PullNotificationTrigger notificationTrigger()
	{
		PullNotificationTrigger trigger = config.pullNotificationTrigger();
		return trigger == null ? PullNotificationTrigger.EVERY_CARD : trigger;
	}

	/** Converts reveal-service cards into {@link PullNotificationMessages.PackPull}s, computing notify-eligibility for each. */
	public List<PullNotificationMessages.PackPull> packPullsFromCards(List<RevealCard> cards)
	{
		List<PullNotificationMessages.PackPull> pulls = new ArrayList<>();
		if (cards == null)
		{
			return pulls;
		}
		for (RevealCard card : cards)
		{
			if (card == null || card.getPull() == null || card.getPull().getCardName() == null)
			{
				continue;
			}
			pulls.add(new PullNotificationMessages.PackPull(
				card.getPull().getCardName().trim(),
				card.isNew(),
				card.getPull().isFoil(),
				card.getTier(),
				card.getPull().getInstanceId(),
				shouldNotify(card.getTier(), card.getPull().isFoil(), card.isNew())));
		}
		return pulls;
	}

	/**
	 * Builds the end-of-pack summary content, or empty if no pull is notification-eligible or both
	 * summary sections end up empty.
	 */
	public Optional<PackSummaryContent> packSummaryContent(List<PullNotificationMessages.PackPull> pulls)
	{
		if (!PullNotificationMessages.hasEligiblePull(pulls))
		{
			return Optional.empty();
		}
		PullNotificationMessages.PackSummarySections sections = PullNotificationMessages.buildSummarySections(pulls);
		if (sections.newCards.isEmpty() && sections.duplicates.isEmpty())
		{
			return Optional.empty();
		}
		PullNotificationMessages.PackPull thumbnailPull = PullNotificationMessages.highestTierPull(pulls);
		String imageUrl = thumbnailPull == null ? "" : cardImageUrl(thumbnailPull.cardName);
		RarityMath.Tier tier = thumbnailPull == null ? null : thumbnailPull.tier;
		return Optional.of(new PackSummaryContent(sections, imageUrl, tier));
	}

	/** Builds the message text, card image URL, and inspect URL for a single-card notification. */
	public PullCardContent pullCardContent(
		String cardName, boolean newForCollection, boolean foil, String instanceId, String opener)
	{
		String trimmed = cardName.trim();
		String inspectUrl = PullNotificationMessages.inspectUrl(instanceId);
		return new PullCardContent(
			PullNotificationMessages.collectionMessage(opener, trimmed, newForCollection, foil, inspectUrl),
			cardImageUrl(trimmed),
			inspectUrl);
	}

	/** Resolves a card's public image URL (as .webp), or "" if the card is unknown or has no image. */
	public String cardImageUrl(String cardName)
	{
		return cardDatabase.findByName(cardName)
			.map(CardDefinition::getImageUrl)
			.map(CloudEndpoints::resolvePublicUrl)
			.map(PullNotifySupport::toWebpUrl)
			.orElse("");
	}

	/** Rewrites a ".png" image URL to ".webp"; passes other URLs through unchanged. */
	private static String toWebpUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return "";
		}
		return url.endsWith(".png") ? url.substring(0, url.length() - 4) + ".webp" : url;
	}

	/** Renders the plain-text public collection stats line shown on external notifications. */
	public String statsPlainLine()
	{
		return tcgChatStatsShareService.buildPlainLine(tcgPublicStatsCalculator.computeLive());
	}

	/** Appends the public stats line to a notification message, separated by a blank line. */
	public String messageWithStatsLine(String message)
	{
		return message + "\n\n" + statsPlainLine();
	}

	/** True if {@code tier} meets or exceeds {@code floor} (defaulting to MYTHIC, the strictest, when floor is unset). */
	private static boolean meetsTier(RarityMath.Tier tier, PullNotifyTier floor)
	{
		if (tier == null)
		{
			return false;
		}
		PullNotifyTier minimum = floor == null ? PullNotifyTier.MYTHIC : floor;
		return minimum.meetsOrExceeds(tier);
	}
}

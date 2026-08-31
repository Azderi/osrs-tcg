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

@Singleton
public class PullNotifySupport
{
	public static final class PackSummaryContent
	{
		public final PullNotificationMessages.PackSummarySections sections;
		public final String imageUrl;
		public final RarityMath.Tier tier;

		PackSummaryContent(PullNotificationMessages.PackSummarySections sections, String imageUrl, RarityMath.Tier tier)
		{
			this.sections = sections;
			this.imageUrl = imageUrl;
			this.tier = tier;
		}

		public String messageFor(String opener)
		{
			return PullNotificationMessages.packSummaryMessage(opener, sections);
		}
	}

	public static final class PullCardContent
	{
		public final String description;
		public final String imageUrl;
		public final String inspectUrl;

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

	public PullNotificationTrigger notificationTrigger()
	{
		PullNotificationTrigger trigger = config.pullNotificationTrigger();
		return trigger == null ? PullNotificationTrigger.EVERY_CARD : trigger;
	}

	public List<PullNotificationMessages.PackPull> packPullsFromRevealCards(List<RevealCard> cards)
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

	public String cardImageUrl(String cardName)
	{
		return cardDatabase.findByName(cardName)
			.map(CardDefinition::getImageUrl)
			.map(CloudEndpoints::resolvePublicUrl)
			.map(PullNotifySupport::toWebpUrl)
			.orElse("");
	}

	private static String toWebpUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return "";
		}
		return url.endsWith(".png") ? url.substring(0, url.length() - 4) + ".webp" : url;
	}

	public String statsPlainLine()
	{
		return tcgChatStatsShareService.buildPlainLine(tcgPublicStatsCalculator.computeLive());
	}

	public String messageWithStatsLine(String message)
	{
		return message + "\n\n" + statsPlainLine();
	}

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

package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.config.PullNotifyTier;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.notify.PullNotificationMessages.PackPull;
import com.osrstcg.notify.PullNotificationMessages.PackSummarySections;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class PullNotifySupport
{
	public static final class PackSummaryContent
	{
		public final PackSummarySections sections;
		public final String summaryMessage;
		public final String imageUrl;
		public final RarityMath.Tier tier;

		PackSummaryContent(
			PackSummarySections sections,
			String summaryMessage,
			String imageUrl,
			RarityMath.Tier tier)
		{
			this.sections = sections;
			this.summaryMessage = summaryMessage;
			this.imageUrl = imageUrl;
			this.tier = tier;
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

	public Optional<PackSummaryContent> packSummaryContent(List<PackPull> pulls, String opener)
	{
		if (!PullNotificationMessages.hasEligiblePull(pulls))
		{
			return Optional.empty();
		}
		PackSummarySections sections = PullNotificationMessages.buildSummarySections(pulls);
		if (sections.newCards.isEmpty() && sections.duplicates.isEmpty())
		{
			return Optional.empty();
		}
		PackPull thumbnailPull = PullNotificationMessages.highestTierPull(pulls);
		String imageUrl = thumbnailPull == null ? "" : cardImageUrl(thumbnailPull.cardName);
		RarityMath.Tier tier = thumbnailPull == null ? null : thumbnailPull.tier;
		return Optional.of(new PackSummaryContent(
			sections,
			PullNotificationMessages.packSummaryMessage(opener, sections),
			imageUrl,
			tier));
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

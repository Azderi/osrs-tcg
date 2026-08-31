package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.config.PullNotifyTier;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class PullNotifySupport
{
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

	public static boolean meetsTier(RarityMath.Tier tier, PullNotifyTier floor)
	{
		if (tier == null)
		{
			return false;
		}
		PullNotifyTier minimum = floor == null ? PullNotifyTier.MYTHIC : floor;
		return minimum.meetsOrExceeds(tier);
	}
}

package com.osrstcg.service;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.util.PullNotificationMessages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Sends pack-pull webhook requests to the <a href="https://github.com/pajlads/DinkPlugin">Dink</a> plugin.
 * Requires Dink's {@code External Plugin Requests > Enable External Plugin Notifications}.
 */
@Slf4j
@Singleton
public class DinkNotificationService
{
	static final class PackPull
	{
		private final String cardName;
		private final boolean newForCollection;
		private final boolean foil;
		private final RarityMath.Tier tier;
		private final boolean notificationEligible;

		PackPull(
			String cardName,
			boolean newForCollection,
			boolean foil,
			RarityMath.Tier tier,
			boolean notificationEligible)
		{
			this.cardName = cardName;
			this.newForCollection = newForCollection;
			this.foil = foil;
			this.tier = tier;
			this.notificationEligible = notificationEligible;
		}
	}

	private static final String DINK_NAMESPACE = "dink";
	private static final String DINK_NOTIFY = "notify";
	private static final String SOURCE_PLUGIN = "OSRS TCG";
	private static final String EMBED_TITLE = "OSRS TCG";

	private final EventBus eventBus;
	private final CardDatabase cardDatabase;
	private final WikiImageCacheService wikiImageCacheService;
	private final TcgPublicStatsCalculator tcgPublicStatsCalculator;
	private final TcgChatStatsShareService tcgChatStatsShareService;

	@Inject
	DinkNotificationService(
		EventBus eventBus,
		CardDatabase cardDatabase,
		WikiImageCacheService wikiImageCacheService,
		TcgPublicStatsCalculator tcgPublicStatsCalculator,
		TcgChatStatsShareService tcgChatStatsShareService)
	{
		this.eventBus = eventBus;
		this.cardDatabase = cardDatabase;
		this.wikiImageCacheService = wikiImageCacheService;
		this.tcgPublicStatsCalculator = tcgPublicStatsCalculator;
		this.tcgChatStatsShareService = tcgChatStatsShareService;
	}

	public void notifyPackPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String trimmed = cardName.trim();
		String tierLabel = tier == null ? "" : tier.getLabel();
		String imageUrl = resolveCardImageUrl(trimmed);

		Map<String, Object> data = new HashMap<>();
		data.put("sourcePlugin", SOURCE_PLUGIN);
		data.put("text", messageWithStatsLine(
			PullNotificationMessages.dinkCollectionMessage(trimmed, newForCollection, foil)));
		data.put("title", EMBED_TITLE);
		data.put("imageRequested", true);
		if (!imageUrl.isEmpty())
		{
			data.put("thumbnail", imageUrl);
		}
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("cardName", trimmed);
		metadata.put("foil", foil);
		metadata.put("newForCollection", newForCollection);
		metadata.put("rarityTier", tierLabel);
		if (!imageUrl.isEmpty())
		{
			metadata.put("imageUrl", imageUrl);
		}
		data.put("metadata", metadata);

		try
		{
			eventBus.post(new PluginMessage(DINK_NAMESPACE, DINK_NOTIFY, data));
		}
		catch (Exception ex)
		{
			log.debug("Failed to post Dink notification", ex);
		}
	}

	void notifyPackSummary(List<PackPull> pulls)
	{
		if (!hasNotificationEligiblePull(pulls))
		{
			return;
		}
		List<String> newCards = new ArrayList<>();
		List<String> duplicates = new ArrayList<>();
		List<PackPull> sortedPulls = new ArrayList<>(pulls);
		sortedPulls.sort(Comparator.comparingInt(DinkNotificationService::tierRank).reversed());
		for (PackPull pull : sortedPulls)
		{
			if (pull == null || pull.cardName == null || pull.cardName.trim().isEmpty())
			{
				continue;
			}
			String displayName = pull.cardName.trim() + (pull.foil ? " (foil)" : "");
			if (pull.notificationEligible)
			{
				displayName = "**" + displayName + "**";
			}
			(pull.newForCollection ? newCards : duplicates).add(displayName);
		}
		if (newCards.isEmpty() && duplicates.isEmpty())
		{
			return;
		}

		PackPull thumbnailPull = pulls.get(0);
		String imageUrl = thumbnailPull == null ? "" : resolveCardImageUrl(thumbnailPull.cardName);
		Map<String, Object> data = new HashMap<>();
		data.put("sourcePlugin", SOURCE_PLUGIN);
		data.put("text", messageWithStatsLine(PullNotificationMessages.dinkPackSummaryMessage(newCards, duplicates)));
		data.put("title", EMBED_TITLE);
		data.put("imageRequested", true);
		if (!imageUrl.isEmpty())
		{
			data.put("thumbnail", imageUrl);
		}
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("notificationType", "packSummary");
		metadata.put("newCards", new ArrayList<>(newCards));
		metadata.put("duplicates", new ArrayList<>(duplicates));
		data.put("metadata", metadata);

		try
		{
			eventBus.post(new PluginMessage(DINK_NAMESPACE, DINK_NOTIFY, data));
		}
		catch (Exception ex)
		{
			log.debug("Failed to post Dink pack summary", ex);
		}
	}

	static boolean hasNotificationEligiblePull(List<PackPull> pulls)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return false;
		}
		for (PackPull pull : pulls)
		{
			if (pull != null && pull.notificationEligible)
			{
				return true;
			}
		}
		return false;
	}

	private static int tierRank(PackPull pull)
	{
		return pull == null || pull.tier == null ? -1 : pull.tier.ordinal();
	}

	private String messageWithStatsLine(String message)
	{
		return message + "\n\n" + tcgChatStatsShareService.buildPlainLine(tcgPublicStatsCalculator.computeLive());
	}

	private String resolveCardImageUrl(String cardName)
	{
		return cardDatabase.findByName(cardName)
			.map(CardDefinition::getImageUrl)
			.map(wikiImageCacheService::publicImageUrl)
			.orElse("");
	}
}

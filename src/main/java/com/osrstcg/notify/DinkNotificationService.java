package com.osrstcg.notify;

import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
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
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.interop.TcgPublicStatsCalculator;

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
		private final String instanceId;
		private final boolean notificationEligible;

		PackPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier)
		{
			this(cardName, newForCollection, foil, tier, null);
		}

		PackPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId)
		{
			this(cardName, newForCollection, foil, tier, instanceId, false);
		}

		PackPull(
			String cardName,
			boolean newForCollection,
			boolean foil,
			RarityMath.Tier tier,
			String instanceId,
			boolean notificationEligible)
		{
			this.cardName = cardName;
			this.newForCollection = newForCollection;
			this.foil = foil;
			this.tier = tier;
			this.instanceId = instanceId;
			this.notificationEligible = notificationEligible;
		}
	}

	private static final String DINK_NAMESPACE = "dink";
	private static final String DINK_NOTIFY = "notify";
	private static final String SOURCE_PLUGIN = "OSRS TCG";
	private static final String EMBED_TITLE = "OSRS TCG";

	private final EventBus eventBus;
	private final CardDatabase cardDatabase;
	private final CardImageCacheService cardImageCacheService;
	private final TcgPublicStatsCalculator tcgPublicStatsCalculator;
	private final TcgChatStatsShareService tcgChatStatsShareService;

	@Inject
	DinkNotificationService(
		EventBus eventBus,
		CardDatabase cardDatabase,
		CardImageCacheService cardImageCacheService,
		TcgPublicStatsCalculator tcgPublicStatsCalculator,
		TcgChatStatsShareService tcgChatStatsShareService)
	{
		this.eventBus = eventBus;
		this.cardDatabase = cardDatabase;
		this.cardImageCacheService = cardImageCacheService;
		this.tcgPublicStatsCalculator = tcgPublicStatsCalculator;
		this.tcgChatStatsShareService = tcgChatStatsShareService;
	}

	public void notifyPackPull(String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier)
	{
		notifyPackPull(cardName, newForCollection, foil, tier, null);
	}

	public void notifyPackPull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String trimmed = cardName.trim();
		String tierLabel = tier == null ? "" : tier.getLabel();
		String imageUrl = resolveCardImageUrl(trimmed);
		String inspectUrl = PullNotificationMessages.inspectUrl(instanceId);

		Map<String, Object> data = new HashMap<>();
		data.put("sourcePlugin", SOURCE_PLUGIN);
		data.put("text", messageWithStatsLine(
			PullNotificationMessages.dinkCollectionMessage(trimmed, newForCollection, foil, inspectUrl)));
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
		if (!inspectUrl.isEmpty())
		{
			metadata.put("inspectUrl", inspectUrl);
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
		List<String> rarityTiers = new ArrayList<>();
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
			String inspectUrl = PullNotificationMessages.inspectUrl(pull.instanceId);
			if (!inspectUrl.isEmpty())
			{
				displayName = displayName + " — [Inspect](" + inspectUrl + ")";
			}
			(pull.newForCollection ? newCards : duplicates).add(displayName);
			rarityTiers.add(pull.tier == null ? "" : pull.tier.getLabel());
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
		metadata.put("rarityTiers", new ArrayList<>(rarityTiers));
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
			.map(cardImageCacheService::resolveAbsoluteUrl)
			.orElse("");
	}
}

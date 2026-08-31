package com.osrstcg.notify;

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
import com.osrstcg.catalog.RarityMath;

@Slf4j
@Singleton
public class DinkNotificationService
{
	static final class PackPull
	{
		final String cardName;
		final boolean newForCollection;
		final boolean foil;
		final RarityMath.Tier tier;
		final String instanceId;
		final boolean notificationEligible;

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
	private static final String PLUGIN_TITLE = "OSRS TCG";

	private final EventBus eventBus;
	private final PullNotifySupport pullNotifySupport;

	@Inject
	DinkNotificationService(EventBus eventBus, PullNotifySupport pullNotifySupport)
	{
		this.eventBus = eventBus;
		this.pullNotifySupport = pullNotifySupport;
	}

	public void notifyPackPull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		String trimmed = cardName.trim();
		String imageUrl = pullNotifySupport.cardImageUrl(trimmed);
		String inspectUrl = PullNotificationMessages.inspectUrl(instanceId);
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("cardName", trimmed);
		metadata.put("foil", foil);
		metadata.put("newForCollection", newForCollection);
		metadata.put("rarityTier", tier == null ? "" : tier.getLabel());
		if (!imageUrl.isEmpty())
		{
			metadata.put("imageUrl", imageUrl);
		}
		if (!inspectUrl.isEmpty())
		{
			metadata.put("inspectUrl", inspectUrl);
		}
		postNotify(
			pullNotifySupport.messageWithStatsLine(
				PullNotificationMessages.collectionMessage("%USERNAME%", trimmed, newForCollection, foil, inspectUrl)),
			imageUrl,
			metadata);
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
			(pull.newForCollection ? newCards : duplicates).add(formatSummaryLine(pull));
		}
		if (newCards.isEmpty() && duplicates.isEmpty())
		{
			return;
		}
		PackPull thumbnailPull = pulls.get(0);
		String imageUrl = thumbnailPull == null ? "" : pullNotifySupport.cardImageUrl(thumbnailPull.cardName);
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("notificationType", "packSummary");
		metadata.put("newCards", new ArrayList<>(newCards));
		metadata.put("duplicates", new ArrayList<>(duplicates));
		postNotify(
			pullNotifySupport.messageWithStatsLine(
				PullNotificationMessages.dinkPackSummaryMessage(newCards, duplicates)),
			imageUrl,
			metadata);
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

	private static String formatSummaryLine(PackPull pull)
	{
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
		return displayName;
	}

	private static int tierRank(PackPull pull)
	{
		return pull == null || pull.tier == null ? -1 : pull.tier.ordinal();
	}

	private void postNotify(String text, String imageUrl, Map<String, Object> metadata)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("sourcePlugin", PLUGIN_TITLE);
		data.put("text", text);
		data.put("title", PLUGIN_TITLE);
		data.put("imageRequested", true);
		if (imageUrl != null && !imageUrl.isEmpty())
		{
			data.put("thumbnail", imageUrl);
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
}

package com.osrstcg.notify;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.notify.PullNotifySupport.PackSummaryContent;

@Slf4j
@Singleton
public class DinkNotificationService
{
	private static final String DINK_NAMESPACE = "dink";
	private static final String DINK_NOTIFY = "notify";
	private static final String DINK_USERNAME = "%USERNAME%";

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
		if (PullNotificationMessages.isBlank(cardName))
		{
			return;
		}
		PullNotifySupport.PullCardContent content = pullNotifySupport.pullCardContent(
			cardName, newForCollection, foil, instanceId, DINK_USERNAME);
		postNotify(
			pullNotifySupport.messageWithStatsLine(content.description),
			content.imageUrl,
			pullMetadata(cardName.trim(), foil, newForCollection, tier, content.imageUrl, content.inspectUrl));
	}

	void notifyPackSummary(PackSummaryContent content)
	{
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("notificationType", "packSummary");
		metadata.put("newCards", content.sections.newCards);
		metadata.put("duplicates", content.sections.duplicates);
		postNotify(
			pullNotifySupport.messageWithStatsLine(content.messageFor(DINK_USERNAME)),
			content.imageUrl,
			metadata);
	}

	private static Map<String, Object> pullMetadata(
		String cardName, boolean foil, boolean newForCollection, RarityMath.Tier tier,
		String imageUrl, String inspectUrl)
	{
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("cardName", cardName);
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
		return metadata;
	}

	private void postNotify(String text, String imageUrl, Map<String, Object> metadata)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("sourcePlugin", PullNotificationMessages.PLUGIN_TITLE);
		data.put("text", text);
		data.put("title", PullNotificationMessages.PLUGIN_TITLE);
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

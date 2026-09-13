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
import com.osrstcg.state.TcgPublicStats;
/**
 * Forwards pull and pack-summary notifications to the Dink plugin via its {@link PluginMessage}
 * namespace, so Dink can relay them (e.g. to Discord) independently of this plugin's own webhook.
 */
@Slf4j
@Singleton
public class DinkNotificationService
{
	private static final String DINK_NAMESPACE = "dink";
	private static final String DINK_NOTIFY = "notify";
	private static final String DINK_USERNAME = "%USERNAME%";

	private final EventBus eventBus;
	private final PullNotifySupport pullNotifySupport;
/** Wires the event bus used to post {@link PluginMessage}s and the shared pull-content builder. */
	@Inject
	DinkNotificationService(EventBus eventBus, PullNotifySupport pullNotifySupport)
	{
		this.eventBus = eventBus;
		this.pullNotifySupport = pullNotifySupport;
	}
/** Posts a single-card pull notification to Dink, with card/rarity metadata attached. No-op for a blank card name. */
	public void notifyPackPull(
		String cardName, boolean newForCollection, boolean foil, RarityMath.Tier tier, String instanceId,
		Double condition, long score, Long pulledAtEpochMs)
	{
		if (PullNotificationMessages.isBlank(cardName))
		{
			return;
		}
		PullNotifySupport.PullCardContent content = pullNotifySupport.pullCardContent(
			cardName, newForCollection, foil, instanceId, DINK_USERNAME, condition);
		TcgPublicStats stats = pullNotifySupport.currentStats();
		Map<String, Object> metadata = pullMetadata(cardName.trim(), foil, newForCollection, tier, score, pulledAtEpochMs, content);
		metadata.put("collectionStats", PullNotifySupport.collectionStatsSummary(stats));
		postNotify(
			pullNotifySupport.messageWithStatsLine(content.description, stats),
			content.imageUrl,
			metadata);
	}
/** Posts an end-of-pack summary notification (new cards / duplicates) to Dink. */
	void notifyPackSummary(PackSummaryContent content)
	{
		TcgPublicStats stats = pullNotifySupport.currentStats();
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("notificationType", "packSummary");
		metadata.put("newCards", content.newCardDetails);
		metadata.put("duplicates", content.duplicateDetails);
		metadata.put("collectionStats", PullNotifySupport.collectionStatsSummary(stats));
		postNotify(
			pullNotifySupport.messageWithStatsLine(content.messageFor(DINK_USERNAME), stats),
			content.imageUrl,
			metadata);
	}
/** Builds the Dink metadata map for a single card pull (name, foil, tier, score, catalog tags, links, pull time). */
	private static Map<String, Object> pullMetadata(
		String cardName, boolean foil, boolean newForCollection, RarityMath.Tier tier,
		long score, Long pulledAtEpochMs, PullNotifySupport.PullCardContent content)
	{
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("cardName", cardName);
		metadata.put("foil", foil);
		metadata.put("newForCollection", newForCollection);
		metadata.put("rarityTier", tier == null ? "" : tier.getLabel());
		metadata.put("score", score);
		metadata.put("category", content.category);
		metadata.put("regions", content.regions);
		if (content.condition != null)
		{
			metadata.put("condition", content.condition);
		}
		if (!content.imageUrl.isEmpty())
		{
			metadata.put("imageUrl", content.imageUrl);
		}
		if (!content.inspectUrl.isEmpty())
		{
			metadata.put("inspectUrl", content.inspectUrl);
		}
		String pulledAt = PullNotificationMessages.pulledAtIso(pulledAtEpochMs);
		if (pulledAt != null)
		{
			metadata.put("pulledAt", pulledAt);
		}
		return metadata;
	}
/** Assembles and posts the Dink {@link PluginMessage} envelope; swallows failures (Dink not installed, etc.). */
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

package com.osrstcg.notify;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.notify.PullNotificationMessages.PackPull;
import com.osrstcg.notify.PullNotifySupport.PackSummaryContent;
import com.osrstcg.catalog.RarityMath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Covers the structured (non-markdown) pack-summary metadata {@link DinkNotificationService} posts to Dink. */
public class DinkNotificationServiceTest
{
	@Test
	public void packSummaryMetadataCarriesStructuredPerCardDetails()
	{
		PullNotifySupport support = PullNotifySupportTest.newSupport(true, whiteBeretDefinition());
		PackPull whiteBeret = new PackPull(
			"White beret", true, false, RarityMath.Tier.LEGENDARY, "instance-1", true, null, 8200L, null);
		PackSummaryContent content = support.packSummaryContent(List.of(whiteBeret)).orElseThrow();

		EventBus eventBus = new EventBus();
		List<PluginMessage> captured = new ArrayList<>();
		eventBus.register(PluginMessage.class, captured::add, 0f);

		new DinkNotificationService(eventBus, support).notifyPackSummary(content);

		assertEquals(1, captured.size());
		PluginMessage message = captured.get(0);
		assertEquals("dink", message.getNamespace());
		assertEquals("notify", message.getName());
		@SuppressWarnings("unchecked")
		Map<String, Object> metadata = (Map<String, Object>) message.getData().get("metadata");
		assertEquals("packSummary", metadata.get("notificationType"));
		assertSame(content.newCardDetails, metadata.get("newCards"));
		assertSame(content.duplicateDetails, metadata.get("duplicates"));
	}

	private static CardDefinition whiteBeretDefinition()
	{
		CardDefinition def = new CardDefinition();
		def.setName("White beret");
		def.setCategory(new ArrayList<>(List.of("Clothing")));
		def.setRegions(new ArrayList<>(List.of("Kandarin")));
		return def;
	}
}

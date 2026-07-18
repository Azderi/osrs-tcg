package com.osrstcg.service;

import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OwnedCardNamesApiServiceTest
{
	private final List<PluginMessage> outbound = new ArrayList<>();
	private TcgStateService stateService;
	private OwnedCardNamesApiService api;
	private EventBus eventBus;

	@Before
	public void setUp()
	{
		outbound.clear();
		stateService = new TcgStateService(com.osrstcg.model.TcgState.empty());
		stateService.addCard("Cow", false, 1);
		stateService.addCard("Cow", true, 1);
		stateService.addCard("Chicken", false, 2);

		eventBus = new EventBus();
		eventBus.register(new Object()
		{
			@Subscribe
			public void onPluginMessage(PluginMessage event)
			{
				if (event != null
					&& OwnedCardNamesApiService.NAMESPACE.equals(event.getNamespace())
					&& (OwnedCardNamesApiService.REPLY.equals(event.getName())
					|| OwnedCardNamesApiService.CHANGED.equals(event.getName())))
				{
					outbound.add(event);
				}
			}
		});
		api = new OwnedCardNamesApiService(eventBus, stateService);
		api.start();
	}

	@Test
	public void distinctOwnedNamesFoldsFoilAndNormal()
	{
		List<String> names = OwnedCardNamesApiService.distinctOwnedNames(
			CollectionState.copyOf(List.of(
				OwnedCardInstance.createNew("Abyssal whip", false, "P", 1L),
				OwnedCardInstance.createNew("Abyssal whip", true, "P", 2L),
				OwnedCardInstance.createNew("Dragon scimitar", false, "P", 3L)
			)));
		Assert.assertEquals(List.of("Abyssal whip", "Dragon scimitar"), names);
	}

	@Test
	public void queryRepliesWithOwnedNamesOnly()
	{
		eventBus.post(new PluginMessage(
			OwnedCardNamesApiService.NAMESPACE, OwnedCardNamesApiService.QUERY));

		Assert.assertEquals(1, outbound.size());
		PluginMessage reply = outbound.get(0);
		Assert.assertEquals(OwnedCardNamesApiService.REPLY, reply.getName());
		Assert.assertEquals(1, reply.getData().size());
		@SuppressWarnings("unchecked")
		List<String> names = (List<String>) reply.getData().get(OwnedCardNamesApiService.KEY_OWNED_NAMES);
		Assert.assertEquals(List.of("Chicken", "Cow"), names);
	}

	@Test
	public void collectionChangePushesOwnedNames()
	{
		outbound.clear();
		stateService.addCard("Goblin", false, 1);
		Assert.assertEquals(1, outbound.size());
		Assert.assertEquals(OwnedCardNamesApiService.CHANGED, outbound.get(0).getName());
		@SuppressWarnings("unchecked")
		List<String> names = (List<String>) outbound.get(0).getData().get(OwnedCardNamesApiService.KEY_OWNED_NAMES);
		Assert.assertTrue(names.contains("Goblin"));
	}

	@Test
	public void ignoresNonQueryMessages()
	{
		eventBus.post(new PluginMessage(
			OwnedCardNamesApiService.NAMESPACE, "mutate", Map.of("ownedNames", List.of("Hack"))));
		Assert.assertTrue(outbound.isEmpty());
	}
}

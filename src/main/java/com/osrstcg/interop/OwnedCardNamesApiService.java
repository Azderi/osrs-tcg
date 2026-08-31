package com.osrstcg.interop;

import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CollectionState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import com.osrstcg.state.TcgStateService;

/**
 * Read-only {@link PluginMessage} API for sibling plugins (not an open HTTP proxy).
 * Query: {@code new PluginMessage(NAMESPACE, QUERY)} → {@link #REPLY}.
 * Push: {@link #CHANGED} after collection mutations.
 * Payload: owned names, foil names, item/NPC ids, and {@link #KEY_GROUP_KEY}.
 */
@Slf4j
@Singleton
public class OwnedCardNamesApiService
{
	public static final String NAMESPACE = "osrstcg";
	public static final String QUERY = "query-owned-names";
	public static final String REPLY = "owned-names";
	public static final String CHANGED = "owned-names-changed";
	public static final String KEY_OWNED_NAMES = "ownedNames";
	public static final String KEY_OWNED_FOIL_NAMES = "ownedFoilNames";
	public static final String KEY_OWNED_ITEM_IDS = "ownedItemIds";
	public static final String KEY_OWNED_NPC_IDS = "ownedNpcIds";
	public static final String KEY_GROUP_KEY = "groupKey";

	private final EventBus eventBus;
	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final Runnable onCollectionChanged = this::broadcastChanged;

	@Inject
	OwnedCardNamesApiService(
		EventBus eventBus,
		TcgStateService stateService,
		CardDatabase cardDatabase)
	{
		this.eventBus = eventBus;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
	}

	public void start()
	{
		if (!started.compareAndSet(false, true))
		{
			return;
		}
		eventBus.register(this);
		stateService.addOwnedCollectionListener(onCollectionChanged);
	}

	public void stop()
	{
		if (!started.compareAndSet(true, false))
		{
			return;
		}
		stateService.removeOwnedCollectionListener(onCollectionChanged);
		eventBus.unregister(this);
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!started.get()
			|| event == null
			|| !NAMESPACE.equals(event.getNamespace())
			|| !QUERY.equals(event.getName()))
		{
			return;
		}
		post(REPLY, snapshotPayload());
	}

	public void broadcastChanged()
	{
		if (!started.get())
		{
			return;
		}
		post(CHANGED, snapshotPayload());
	}

	private Map<String, Object> snapshotPayload()
	{
		CollectionState collection;
		synchronized (stateService)
		{
			collection = stateService.getState().getCollectionState();
		}
		List<String> ownedNames = distinctOwnedNames(collection);
		List<String> ownedFoilNames = distinctOwnedFoilNames(collection);
		Set<Long> itemIds = new TreeSet<>();
		Set<Long> npcIds = new TreeSet<>();
		collectOwnedCatalogIds(ownedNames, itemIds, npcIds);

		Map<String, Object> data = new HashMap<>();
		data.put(KEY_OWNED_NAMES, ownedNames);
		data.put(KEY_OWNED_FOIL_NAMES, ownedFoilNames);
		data.put(KEY_OWNED_ITEM_IDS, List.copyOf(itemIds));
		data.put(KEY_OWNED_NPC_IDS, List.copyOf(npcIds));
		data.put(KEY_GROUP_KEY, stateService.getCloudGroupKey());
		return data;
	}

	private void collectOwnedCatalogIds(List<String> ownedNames, Set<Long> itemIds, Set<Long> npcIds)
	{
		if (ownedNames == null || ownedNames.isEmpty())
		{
			return;
		}
		for (String name : ownedNames)
		{
			cardDatabase.findByName(name).ifPresent(card ->
			{
				Set<Long> target = isNpcCard(card) ? npcIds : itemIds;
				addCatalogIds(card, target);
			});
		}
	}

	static void addCatalogIds(CardDefinition card, Set<Long> target)
	{
		if (card == null || target == null)
		{
			return;
		}
		if (card.getId() != null)
		{
			target.add(card.getId());
		}
		List<Long> variants = card.getVariantIds();
		if (variants == null)
		{
			return;
		}
		for (Long variantId : variants)
		{
			if (variantId != null)
			{
				target.add(variantId);
			}
		}
	}

	static boolean isNpcCard(CardDefinition card)
	{
		if (card == null)
		{
			return false;
		}
		for (String tag : card.getCategoryTags())
		{
			if (tag != null && "NPC".equalsIgnoreCase(tag.trim()))
			{
				return true;
			}
		}
		return false;
	}

	static List<String> distinctOwnedNames(CollectionState collection)
	{
		return distinctOwnedNames(collection, false);
	}

	static List<String> distinctOwnedFoilNames(CollectionState collection)
	{
		return distinctOwnedNames(collection, true);
	}

	private static List<String> distinctOwnedNames(CollectionState collection, boolean foilOnly)
	{
		if (collection == null)
		{
			return List.of();
		}
		Set<String> names = new LinkedHashSet<>();
		for (CardCollectionKey key : collection.getOwnedCards().keySet())
		{
			if (key == null || (foilOnly && !key.isFoil()))
			{
				continue;
			}
			String name = key.getCardName();
			if (name == null)
			{
				continue;
			}
			String trimmed = name.trim();
			if (!trimmed.isEmpty())
			{
				names.add(trimmed);
			}
		}
		List<String> sorted = new ArrayList<>(names);
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		return sorted;
	}

	private void post(String name, Map<String, Object> data)
	{
		try
		{
			eventBus.post(new PluginMessage(NAMESPACE, name, data));
		}
		catch (Exception ex)
		{
			log.debug("Failed to post {}", name, ex);
		}
	}
}

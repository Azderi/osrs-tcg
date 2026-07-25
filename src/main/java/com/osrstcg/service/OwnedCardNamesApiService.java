package com.osrstcg.service;

import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.CollectionState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;

/**
 * Read-only {@link PluginMessage} API: distinct owned card names (foil and normal folded together).
 * Sibling plugins copy the string constants — they cannot import this class across Hub classloaders.
 * <p>
 * Query: post {@code new PluginMessage(NAMESPACE, QUERY)} → reply {@link #REPLY} with {@link #KEY_OWNED_NAMES}.
 * Push: {@link #CHANGED} with the same payload after collection mutations.
 * <p>
 * When the opt-in Group Ironman shared collection is enabled ({@link GroupCollectionSyncService}),
 * both the reply and the push payload contain the UNION of the local player's names and the pooled
 * teammate names, so downstream challenge plugins (tcg-locked / bronzeman-tcg) see a card as owned
 * if any group member owns it.
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

	private final EventBus eventBus;
	private final TcgStateService stateService;
	private final GroupCollectionSyncService groupCollectionSyncService;
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final Runnable onCollectionChanged = this::broadcastChanged;

	@Inject
	OwnedCardNamesApiService(
		EventBus eventBus, TcgStateService stateService, GroupCollectionSyncService groupCollectionSyncService)
	{
		this.eventBus = eventBus;
		this.stateService = stateService;
		this.groupCollectionSyncService = groupCollectionSyncService;
	}

	public void start()
	{
		if (!started.compareAndSet(false, true))
		{
			return;
		}
		eventBus.register(this);
		stateService.addCollectionChangeListener(onCollectionChanged);
		groupCollectionSyncService.setChangeListener(onCollectionChanged);
	}

	public void stop()
	{
		if (!started.compareAndSet(true, false))
		{
			return;
		}
		groupCollectionSyncService.setChangeListener(null);
		stateService.removeCollectionChangeListener(onCollectionChanged);
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

	private void broadcastChanged()
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
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_OWNED_NAMES, ownedNames(collection));
		return data;
	}

	private List<String> ownedNames(CollectionState collection)
	{
		List<String> localNames = distinctOwnedNames(collection);
		if (!groupCollectionSyncService.isEnabled())
		{
			return localNames;
		}
		Set<String> union = new LinkedHashSet<>(localNames);
		union.addAll(groupCollectionSyncService.getPooledOwnedNames());
		List<String> sorted = new ArrayList<>(union);
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		return sorted;
	}

	static List<String> distinctOwnedNames(CollectionState collection)
	{
		if (collection == null)
		{
			return List.of();
		}
		Set<String> names = new LinkedHashSet<>();
		for (CardCollectionKey key : collection.getOwnedCards().keySet())
		{
			if (key == null)
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

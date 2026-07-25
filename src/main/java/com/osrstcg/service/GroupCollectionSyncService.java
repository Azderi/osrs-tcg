package com.osrstcg.service;

import com.google.gson.Gson;
import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.model.CardEntry;
import com.osrstcg.persist.GroupCollectionStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Opt-in Group Ironman shared card pool: periodically reads each configured teammate's public
 * osrs-tcg.xyz album ({@code GET /api/v1/players/{displayName}}, the same endpoint the website
 * uses) and unions their owned card NAMES with the local player's, so a card owned by ANY
 * teammate counts as owned by everyone for challenge-plugin gating ({@link OwnedCardNamesApiService}).
 * <p>
 * Only card ownership is pooled — never credits, packs, foils or pity/rewardTuning state, and
 * teammates' cards are never merged into the real {@link TcgStateService} collection (that would
 * corrupt credits/economy/dedupe on reload). The pool is persisted separately via
 * {@link GroupCollectionStore} so it is available immediately at startup (before any network
 * call), survives client restarts, and keeps working regardless of who else is online.
 * <p>
 * The pool only ever grows: a teammate's last successfully fetched name set is cached in memory
 * and a failed/404 fetch simply leaves that teammate's cached names untouched, so a transient
 * outage (or a teammate who hasn't published an album yet) can never shrink the gating set.
 */
@Slf4j
@Singleton
public class GroupCollectionSyncService
{
	private static final long INITIAL_SYNC_DELAY_MS = 5_000L;
	private static final long SYNC_PERIOD_MS = 5L * 60L * 1000L; // 5 minutes

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final OsrsTcgConfig config;
	private final TcgStateService stateService;
	private final GroupCollectionStore store;
	private final ScheduledExecutorService scheduler;

	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean syncInFlight = new AtomicBoolean(false);
	/** Per-teammate last-known owned names (normalized display name -> names). Only updated on a successful fetch. */
	private final ConcurrentMap<String, Set<String>> memberOwnedNames = new ConcurrentHashMap<>();
	private final AtomicReference<Set<String>> pooledOwnedNames = new AtomicReference<>(Set.of());
	private final AtomicReference<Runnable> changeListener = new AtomicReference<>(null);
	private final Runnable onLocalCollectionChanged = this::onLocalCollectionChanged;

	private ScheduledFuture<?> periodicFuture;

	@Inject
	GroupCollectionSyncService(
		OkHttpClient okHttpClient,
		Gson gson,
		OsrsTcgConfig config,
		TcgStateService stateService,
		GroupCollectionStore store,
		ScheduledExecutorService scheduler)
	{
		this.httpClient = okHttpClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.config = config;
		this.stateService = stateService;
		this.store = store;
		this.scheduler = scheduler;
	}

	public void start()
	{
		if (!started.compareAndSet(false, true))
		{
			return;
		}
		// Load the persisted floor before any fetch so downstream readers see last-known names immediately.
		pooledOwnedNames.set(new LinkedHashSet<>(store.load()));
		stateService.addCollectionChangeListener(onLocalCollectionChanged);
		periodicFuture = scheduler.scheduleWithFixedDelay(
			this::syncNow, INITIAL_SYNC_DELAY_MS, SYNC_PERIOD_MS, TimeUnit.MILLISECONDS);
		recomputeAndMaybeNotify();
	}

	public void stop()
	{
		if (!started.compareAndSet(true, false))
		{
			return;
		}
		stateService.removeCollectionChangeListener(onLocalCollectionChanged);
		if (periodicFuture != null)
		{
			periodicFuture.cancel(false);
			periodicFuture = null;
		}
		memberOwnedNames.clear();
	}

	/** Call on login / RSProfile switch: reset the in-memory per-teammate cache and reload the floor for this profile. */
	public void onProfileChanged()
	{
		memberOwnedNames.clear();
		pooledOwnedNames.set(new LinkedHashSet<>(store.load()));
		if (started.get() && config.groupCollectionEnabled())
		{
			scheduler.execute(this::syncNow);
		}
	}

	public void setChangeListener(Runnable listener)
	{
		changeListener.set(listener);
	}

	public boolean isEnabled()
	{
		return config.groupCollectionEnabled();
	}

	/** Union of local + all cached teammate names. Empty when group mode is disabled. */
	public Set<String> getPooledOwnedNames()
	{
		if (!isEnabled())
		{
			return Set.of();
		}
		return Set.copyOf(pooledOwnedNames.get());
	}

	/** Call after config changes affecting {@code groupCollectionEnabled} / {@code groupMembers}. */
	public void onConfigChanged()
	{
		if (!started.get())
		{
			return;
		}
		if (config.groupCollectionEnabled())
		{
			scheduler.execute(this::syncNow);
		}
		else
		{
			recomputeAndMaybeNotify();
		}
	}

	private void onLocalCollectionChanged()
	{
		recomputeAndMaybeNotify();
	}

	/** Fetches every configured teammate's public album and recomputes the pool. Safe to call repeatedly. */
	public void syncNow()
	{
		if (!started.get() || !config.groupCollectionEnabled())
		{
			return;
		}
		if (!syncInFlight.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			for (String member : parseMembers(config.groupMembers()))
			{
				fetchMember(member);
			}
		}
		finally
		{
			syncInFlight.set(false);
			recomputeAndMaybeNotify();
		}
	}

	private void fetchMember(String displayName)
	{
		HttpUrl url = HttpUrl.parse(
			WebShareEndpoints.API_BASE + "/players/" + WebShareEndpoints.encodePathSegment(displayName));
		if (url == null)
		{
			return;
		}
		Request request = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 404)
			{
				// No published album yet (or unpublished) — keep last-known cached names, if any.
				log.debug("No public album found for group member {}", displayName);
				return;
			}
			if (!response.isSuccessful())
			{
				log.debug("Group member fetch for {} failed (HTTP {}); keeping last-known names", displayName,
					response.code());
				return;
			}
			ResponseBody body = response.body();
			String json = body == null ? null : body.string();
			Set<String> names = parseOwnedNames(json, gson);
			memberOwnedNames.put(normalizeMemberKey(displayName), names);
		}
		catch (IOException ex)
		{
			log.debug("Group member fetch for {} errored; keeping last-known names", displayName, ex);
		}
	}

	private void recomputeAndMaybeNotify()
	{
		Set<String> localNames;
		synchronized (stateService)
		{
			localNames = new LinkedHashSet<>(
				OwnedCardNamesApiService.distinctOwnedNames(stateService.getState().getCollectionState()));
		}
		Set<String> union = computeUnion(pooledOwnedNames.get(), localNames, memberOwnedNames);
		Set<String> previous = pooledOwnedNames.getAndSet(union);
		if (previous.equals(union))
		{
			return;
		}
		store.save(union);
		Runnable listener = changeListener.get();
		if (listener != null)
		{
			try
			{
				listener.run();
			}
			catch (Exception ex)
			{
				log.debug("Group pool change listener failed", ex);
			}
		}
	}

	/**
	 * Pure merge: {@code floor} (persisted last-known pool) ∪ {@code localNames} ∪ every cached
	 * teammate's names. Never removes anything — a missing/failed teammate fetch simply omits
	 * that teammate's contribution for this pass while {@code floor} keeps everything ever seen.
	 */
	static Set<String> computeUnion(Set<String> floor, Set<String> localNames, Map<String, Set<String>> memberNames)
	{
		Set<String> union = new LinkedHashSet<>();
		if (floor != null)
		{
			union.addAll(floor);
		}
		if (localNames != null)
		{
			union.addAll(localNames);
		}
		if (memberNames != null)
		{
			for (Set<String> names : memberNames.values())
			{
				if (names != null)
				{
					union.addAll(names);
				}
			}
		}
		List<String> sorted = new ArrayList<>(union);
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		return new LinkedHashSet<>(sorted);
	}

	/** Parses the decoded JSON body of {@code GET /api/v1/players/{name}} into owned card names. */
	static Set<String> parseOwnedNames(String json, Gson gson)
	{
		if (json == null || json.isEmpty())
		{
			return Set.of();
		}
		try
		{
			PlayerSnapshotResponse parsed = gson.fromJson(json, PlayerSnapshotResponse.class);
			if (parsed == null || parsed.cardEntries == null)
			{
				return Set.of();
			}
			Set<String> names = new LinkedHashSet<>();
			for (CardEntry entry : parsed.cardEntries)
			{
				if (entry == null || entry.cardName == null)
				{
					continue;
				}
				String trimmed = entry.cardName.trim();
				if (!trimmed.isEmpty())
				{
					names.add(trimmed);
				}
			}
			return names;
		}
		catch (Exception ex)
		{
			return Set.of();
		}
	}

	/** Splits the free-text {@code groupMembers} config value on commas/newlines, trims, dedupes, preserves order. */
	static List<String> parseMembers(String configured)
	{
		if (configured == null || configured.isEmpty())
		{
			return List.of();
		}
		Set<String> out = new LinkedHashSet<>();
		for (String raw : configured.split("[,\\r\\n]+"))
		{
			if (raw == null)
			{
				continue;
			}
			String trimmed = raw.trim();
			if (!trimmed.isEmpty())
			{
				out.add(trimmed);
			}
		}
		return new ArrayList<>(out);
	}

	private static String normalizeMemberKey(String name)
	{
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	/** Minimal shape of the {@code /players/{name}} response we care about; matches {@link CollectionShareSnapshotBuilder#buildPayload}. */
	private static final class PlayerSnapshotResponse
	{
		List<CardEntry> cardEntries;
	}
}

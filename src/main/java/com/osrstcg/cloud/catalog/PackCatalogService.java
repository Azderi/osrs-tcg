package com.osrstcg.cloud.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardImageCacheService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.cloud.api.CloudApiClient;
/**
 * Holds the in-memory {@link PackCatalogCache} for the shop, fetching it from {@code GET /packs}
 * on login or after a catalog-mismatch error and preloading pack images. Fetches run on the
 * injected scheduler and make a blocking network call, so they must not run on the client thread.
 */
@Slf4j
@Singleton
public final class PackCatalogService
{
	private static final int DEFAULT_PACK_SIZE = 5;

	private final CloudApiClient api;
	private final ScheduledExecutorService scheduler;
	private final CardImageCacheService imageCacheService;

	private final AtomicReference<PackCatalogCache> cache = new AtomicReference<>();
	private final AtomicBoolean loginFetchAttempted = new AtomicBoolean(false);
	private final AtomicReference<Runnable> changeListener = new AtomicReference<>(null);

	@Inject
	PackCatalogService(
		CloudApiClient api,
		ScheduledExecutorService scheduler,
		CardImageCacheService imageCacheService)
	{
		this.api = api;
		this.scheduler = scheduler;
		this.imageCacheService = imageCacheService;
		this.cache.set(emptyCache());
	}
/** Registers a callback invoked (not necessarily on the client thread) whenever the pack catalog changes. */
	public void setChangeListener(Runnable listener)
	{
		changeListener.set(listener);
	}
/** Current catalog snapshot; never null (an empty placeholder before the first successful fetch). */
	public PackCatalogCache getCache()
	{
		return cache.get();
	}
/** Packs to show in the shop: empty until a real server catalog has been loaded. */
	public List<BoosterPackDefinition> getVisibleBoosters()
	{
		PackCatalogCache current = getCache();
		if (!current.isFromServer() || current.isEmpty())
		{
			return List.of();
		}
		return current.getPacks();
	}
/** Finds a pack by collection key or id; returns null if not found or if either argument is unusable. */
	public static BoosterPackDefinition findById(List<BoosterPackDefinition> packs, String packId)
	{
		if (packId == null || packId.isBlank() || packs == null)
		{
			return null;
		}
		for (BoosterPackDefinition pack : packs)
		{
			if (pack == null)
			{
				continue;
			}
			if (packId.equals(pack.getCollectionKey()) || packId.equals(pack.getId()))
			{
				return pack;
			}
		}
		return null;
	}
/** Current catalog version, preferring the cache's, falling back to the API client's last-seen value. */
	public String requireCatalogVersion()
	{
		String version = getCache().getCatalogVersion();
		if (version != null && !version.isBlank())
		{
			return version.trim();
		}
		String fromApi = api.getCachedCatalogVersion();
		return fromApi == null ? "" : fromApi.trim();
	}
/** Fetches the pack catalog once per login: no-op if already attempted since the last {@link #clear()}. */
	public CompletableFuture<Void> refreshOnLogin()
	{
		if (!loginFetchAttempted.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.runAsync(this::fetchAndApplyLogin, scheduler);
	}
/** Forces an async refetch after the server reports a {@code catalog_mismatch} error. */
	public CompletableFuture<Void> refreshAfterCatalogMismatch()
	{
		return CompletableFuture.runAsync(this::fetchAndApplyMismatch, scheduler);
	}
/** Resets to the empty catalog and allows {@link #refreshOnLogin()} to fetch again (e.g. on logout). */
	public void clear()
	{
		loginFetchAttempted.set(false);
		cache.set(emptyCache());
		notifyChanged();
	}
/**
	 * Blocking login fetch-and-apply cycle. Leaves the previous cache in place (shop stays empty
	 * or unchanged) if the fetch fails or the server returns no packs. Kicks off image preload
	 * on success.
	 */
	private void fetchAndApplyLogin()
	{
		try
		{
			JsonObject json = api.getPacks();
			PackCatalogCache parsed = parseServerCatalog(json);
			if (parsed.isEmpty())
			{
				log.error("GET /packs returned empty packs[]; shop stays empty");
				return;
			}
			cache.set(parsed);
			notifyChanged();
			preloadPackImages(parsed).whenComplete((ok, err) -> notifyChanged());
			log.info("Pack catalog loaded from server ({} packs, version={})",
				parsed.getPacks().size(), parsed.getCatalogVersion());
		}
		catch (Exception e)
		{
			log.warn("Login pack catalog fetch failed; shop stays empty", e);
		}
	}
/**
	 * Blocking refetch after a {@code catalog_mismatch} error. Keeps the previous cache if the
	 * fetch fails or the server returns no packs; otherwise applies it and marks the login-fetch
	 * gate satisfied.
	 */
	private void fetchAndApplyMismatch()
	{
		try
		{
			JsonObject json = api.getPacks();
			PackCatalogCache parsed = parseServerCatalog(json);
			if (parsed.isEmpty())
			{
				log.error("catalog_mismatch refetch returned empty packs[]; keeping previous cache");
				return;
			}
			cache.set(parsed);
			loginFetchAttempted.set(true);
			notifyChanged();
			preloadPackImages(parsed).whenComplete((ok, err) -> notifyChanged());
			log.info("Pack catalog refreshed after catalog_mismatch ({} packs, version={})",
				parsed.getPacks().size(), parsed.getCatalogVersion());
		}
		catch (Exception e)
		{
			log.warn("catalog_mismatch pack catalog refetch failed", e);
		}
	}
/** Kicks off async preloading of every hosted thumbnail/image URL referenced by the catalog. */
	private CompletableFuture<Void> preloadPackImages(PackCatalogCache catalog)
	{
		if (catalog == null || imageCacheService == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		List<String> urls = new ArrayList<>();
		for (BoosterPackDefinition pack : catalog.getPacks())
		{
			if (pack == null)
			{
				continue;
			}
			if (BoosterPackDefinition.isHostedImagePath(pack.getThumbnail()))
			{
				urls.add(pack.getThumbnail().trim());
			}
			if (BoosterPackDefinition.isHostedImagePath(pack.getImage()))
			{
				urls.add(pack.getImage().trim());
			}
		}
		if (urls.isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}
		return imageCacheService.preloadAsync(urls);
	}
/** Parses a {@code GET /packs} response body into a {@link PackCatalogCache}, tolerating missing/null fields. */
	static PackCatalogCache parseServerCatalog(JsonObject json)
	{
		String version = "";
		if (json != null && json.has("catalogVersion") && !json.get("catalogVersion").isJsonNull())
		{
			version = json.get("catalogVersion").getAsString();
		}
		int packSize = DEFAULT_PACK_SIZE;
		if (json != null && json.has("packSize") && !json.get("packSize").isJsonNull())
		{
			packSize = Math.max(1, json.get("packSize").getAsInt());
		}
		List<BoosterPackDefinition> packs = new ArrayList<>();
		if (json != null && json.has("packs") && json.get("packs").isJsonArray())
		{
			JsonArray arr = json.getAsJsonArray("packs");
			for (JsonElement el : arr)
			{
				if (!el.isJsonObject())
				{
					continue;
				}
				BoosterPackDefinition pack = parsePackEntry(el.getAsJsonObject());
				if (pack != null)
				{
					packs.add(pack);
				}
			}
		}
		return new PackCatalogCache(version, packSize, packs, true);
	}
/** Parses one {@code packs[]} entry; returns null when {@code id} is missing/blank. */
	private static BoosterPackDefinition parsePackEntry(JsonObject o)
	{
		if (o == null || !o.has("id") || o.get("id").isJsonNull())
		{
			return null;
		}
		String id = o.get("id").getAsString();
		if (id == null || id.isBlank())
		{
			return null;
		}
		BoosterPackDefinition pack = new BoosterPackDefinition();
		pack.setId(id.trim());
		pack.setName(o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : id);
		pack.setPrice(o.has("price") && !o.get("price").isJsonNull() ? o.get("price").getAsInt() : 0);
		if (o.has("thumbnail") && !o.get("thumbnail").isJsonNull())
		{
			String thumb = o.get("thumbnail").getAsString();
			if (thumb != null && !thumb.isBlank())
			{
				pack.setThumbnail(thumb.trim());
			}
		}
		if (o.has("image") && !o.get("image").isJsonNull())
		{
			String image = o.get("image").getAsString();
			if (image != null && !image.isBlank())
			{
				pack.setImage(image.trim());
			}
		}
		if (o.has("category") && o.get("category").isJsonArray())
		{
			List<String> cats = new ArrayList<>();
			for (JsonElement c : o.getAsJsonArray("category"))
			{
				if (c != null && !c.isJsonNull())
				{
					String s = c.getAsString();
					if (s != null && !s.isBlank())
					{
						cats.add(s.trim());
					}
				}
			}
			pack.setCategory(cats);
		}
		else
		{
			pack.setCategory(List.of());
		}
		if (o.has("collectionName") && !o.get("collectionName").isJsonNull())
		{
			String collectionName = o.get("collectionName").getAsString();
			if (collectionName != null && !collectionName.isBlank())
			{
				pack.setCollectionName(collectionName.trim());
			}
		}
		return pack;
	}
/** The placeholder cache used before any successful server fetch, or after {@link #clear()}. */
	private static PackCatalogCache emptyCache()
	{
		return new PackCatalogCache("", 0, List.of(), false);
	}
/** Invokes the registered change listener, if any. */
	private void notifyChanged()
	{
		Runnable listener = changeListener.get();
		if (listener != null)
		{
			listener.run();
		}
	}
}

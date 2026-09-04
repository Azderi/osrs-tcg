package com.osrstcg.cloud.catalog;

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
import com.osrstcg.cloud.api.JsonObjects;
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
		return CompletableFuture.runAsync(() -> fetchAndApply(
			false,
			"GET /packs returned empty packs[]; shop stays empty",
			"Pack catalog loaded from server ({} packs, version={})",
			"Login pack catalog fetch failed; shop stays empty"), scheduler);
	}
/** Forces an async refetch after the server reports a {@code catalog_mismatch} error. */
	public CompletableFuture<Void> refreshAfterCatalogMismatch()
	{
		return CompletableFuture.runAsync(() -> fetchAndApply(
			true,
			"catalog_mismatch refetch returned empty packs[]; keeping previous cache",
			"Pack catalog refreshed after catalog_mismatch ({} packs, version={})",
			"catalog_mismatch pack catalog refetch failed"), scheduler);
	}
/** Resets to the empty catalog and allows {@link #refreshOnLogin()} to fetch again (e.g. on logout). */
	public void clear()
	{
		loginFetchAttempted.set(false);
		cache.set(emptyCache());
		notifyChanged();
	}
/**
	 * Blocking fetch-and-apply. Leaves the previous cache if the fetch fails or returns no packs.
	 * When {@code markLogin} is true, marks the login-fetch gate satisfied on success.
	 */
	private void fetchAndApply(boolean markLogin, String emptyLog, String successLog, String failLog)
	{
		try
		{
			PackCatalogCache parsed = parseServerCatalog(api.getPacks());
			if (parsed.isEmpty())
			{
				log.error(emptyLog);
				return;
			}
			cache.set(parsed);
			if (markLogin)
			{
				loginFetchAttempted.set(true);
			}
			notifyChanged();
			preloadPackImages(parsed).whenComplete((ok, err) -> notifyChanged());
			log.info(successLog, parsed.getPacks().size(), parsed.getCatalogVersion());
		}
		catch (Exception e)
		{
			log.warn(failLog, e);
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
		String version = JsonObjects.text(json, "catalogVersion");
		if (version == null)
		{
			version = "";
		}
		Double packSizeNum = JsonObjects.readNumber(json, "packSize");
		int packSize = packSizeNum == null ? DEFAULT_PACK_SIZE : Math.max(1, (int) Math.round(packSizeNum));
		List<BoosterPackDefinition> packs = new ArrayList<>();
		if (json != null && json.has("packs") && json.get("packs").isJsonArray())
		{
			for (JsonElement el : json.getAsJsonArray("packs"))
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
		String id = JsonObjects.textTrimmed(o, "id");
		if (id == null)
		{
			return null;
		}
		BoosterPackDefinition pack = new BoosterPackDefinition();
		pack.setId(id);
		String name = JsonObjects.text(o, "name");
		pack.setName(name == null ? id : name);
		pack.setPrice(JsonObjects.readInt(o, "price"));
		String thumb = JsonObjects.textTrimmed(o, "thumbnail");
		if (thumb != null)
		{
			pack.setThumbnail(thumb);
		}
		String image = JsonObjects.textTrimmed(o, "image");
		if (image != null)
		{
			pack.setImage(image);
		}
		pack.setCategory(LiveCardsCatalogParser.parseStringList(o, "category"));
		String collectionName = JsonObjects.textTrimmed(o, "collectionName");
		if (collectionName != null)
		{
			pack.setCollectionName(collectionName);
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

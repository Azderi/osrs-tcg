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
 * Login-fetched pack catalog for cloud sessions. Shop and pack-open read the cache only -
 * no GET /packs on shop open. When disconnected or before a successful fetch, the shop is empty.
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

	public void setChangeListener(Runnable listener)
	{
		changeListener.set(listener);
	}

	/** Never null - server snapshot after login fetch, otherwise empty. */
	public PackCatalogCache getCache()
	{
		return cache.get();
	}

	/**
	 * Shop / infobox packs: only the last successful {@code GET /packs} response.
	 * Empty while disconnected or if the login fetch has not succeeded.
	 */
	public List<BoosterPackDefinition> getVisibleBoosters()
	{
		PackCatalogCache current = getCache();
		if (!current.isFromServer() || current.isEmpty())
		{
			return List.of();
		}
		return current.getPacks();
	}

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

	/**
	 * Exactly once after cloud session is established. Failures leave the shop empty for the session.
	 */
	public CompletableFuture<Void> refreshOnLogin()
	{
		if (!loginFetchAttempted.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.runAsync(this::fetchAndApplyLogin, scheduler);
	}

	/** Only from 409 catalog_mismatch on pack open. */
	public CompletableFuture<Void> refreshAfterCatalogMismatch()
	{
		return CompletableFuture.runAsync(this::fetchAndApplyMismatch, scheduler);
	}

	/** Logout / disconnect - clear packs until the next successful login fetch. */
	public void clear()
	{
		loginFetchAttempted.set(false);
		cache.set(emptyCache());
		notifyChanged();
	}

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

	/** Warm disk/memory cache for shop thumbnails and reveal sleeves. */
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

	private static PackCatalogCache emptyCache()
	{
		return new PackCatalogCache("", 0, List.of(), false);
	}

	private void notifyChanged()
	{
		Runnable listener = changeListener.get();
		if (listener != null)
		{
			listener.run();
		}
	}
}

package com.osrstcg.cloud.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.util.AtomicFiles;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;

/**
 * Loads the card catalog from disk cache on startup and keeps it fresh from the cloud
 * {@code catalog/cards/live} endpoint, updating {@link CardDatabase} in place and persisting
 * the raw response to disk for the next launch. Fetches run on the injected scheduler and make
 * a blocking network call, so they must not run on the client thread.
 */
@Slf4j
@Singleton
public final class CardCatalogService
{
	private static final Type LEGACY_CARD_LIST_TYPE = new TypeToken<List<CardDefinition>>() { }.getType();
	private static final String LIVE_CACHE_FILE = "cards.live.json";
	private static final String LIVE_VERSION_FILE = "cards.live.version";
	private static final String CARD_ART_CACHE_FILE = "card-art.json";
	private static final String CARD_ART_VERSION_FILE = "card-art.version";
	private static final String LEGACY_CACHE_FILE = "Card.json";

	private final CloudApiClient api;
	private final Gson gson;
	private final CardDatabase cardDatabase;
	private final ScheduledExecutorService scheduler;
	private final AtomicBoolean loginFetchAttempted = new AtomicBoolean(false);
	private final AtomicReference<Runnable> changeListener = new AtomicReference<>(null);
	private final AtomicReference<String> cachedCatalogVersion = new AtomicReference<>(null);

	@Inject
	CardCatalogService(
		CloudApiClient api,
		Gson gson,
		CardDatabase cardDatabase,
		ScheduledExecutorService scheduler)
	{
		this.api = api;
		this.gson = gson;
		this.cardDatabase = cardDatabase;
		this.scheduler = scheduler;
	}

	/** Registers a callback invoked (not necessarily on the client thread) whenever the catalog changes. */
	public void setChangeListener(Runnable listener)
	{
		changeListener.set(listener);
	}

	/**
	 * Populates {@link CardDatabase} synchronously from the on-disk cache, preferring the live
	 * cache format and falling back to the legacy {@code Card.json} format. No-op if neither
	 * file is present or parseable. Also deletes the now-obsolete card-art overlay cache files.
	 */
	public void loadDiskCacheIfPresent()
	{
		deleteStaleCardArtCache();
		Path live = diskCacheDir().resolve(LIVE_CACHE_FILE);
		if (Files.isRegularFile(live))
		{
			try
			{
				String json = Files.readString(live, StandardCharsets.UTF_8);
				List<CardDefinition> parsed = parseLiveJson(json);
				if (!parsed.isEmpty())
				{
					cardDatabase.replaceCards(parsed, "disk cache");
					cachedCatalogVersion.set(readDiskVersion());
					return;
				}
			}
			catch (Exception ex)
			{
				log.warn("Failed reading live card catalog disk cache {}", live, ex);
			}
		}

		Path legacy = diskCacheDir().resolve(LEGACY_CACHE_FILE);
		if (!Files.isRegularFile(legacy))
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(legacy, StandardCharsets.UTF_8))
		{
			List<CardDefinition> parsed = gson.fromJson(reader, LEGACY_CARD_LIST_TYPE);
			if (parsed == null || parsed.isEmpty())
			{
				return;
			}
			cardDatabase.replaceCards(parsed, "disk cache (legacy)");
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.warn("Failed reading legacy card catalog disk cache {}", legacy, ex);
		}
	}

	/** Kicks off an async catalog fetch on the scheduler; does not gate on the login-fetch flag. */
	public CompletableFuture<Void> prefetchAsync()
	{
		return CompletableFuture.runAsync(this::fetchAndApply, scheduler);
	}

	/** Fetches the catalog once per login: no-op if already attempted since the last {@link #resetLoginFetchGate()}. */
	public CompletableFuture<Void> refreshOnLogin()
	{
		if (!loginFetchAttempted.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.runAsync(this::fetchAndApply, scheduler);
	}

	/** Allows {@link #refreshOnLogin()} to fetch again (e.g. after logout/login). */
	public void resetLoginFetchGate()
	{
		loginFetchAttempted.set(false);
	}

	/** Forces an async catalog fetch regardless of the login-fetch gate, and marks that gate as satisfied. */
	public CompletableFuture<Void> refreshNow()
	{
		loginFetchAttempted.set(true);
		return CompletableFuture.runAsync(this::fetchAndApply, scheduler);
	}

	/**
	 * Blocking fetch-and-apply cycle: sends the cached catalog version as an ETag, and on a 304
	 * loads the disk cache if the in-memory database is still empty; on 200 parses and applies
	 * the new catalog, persisting it to disk. Swallows all exceptions (logging them), except a
	 * consent-required error which is logged at debug and otherwise ignored.
	 */
	private void fetchAndApply()
	{
		try
		{
			String cachedVersion = cachedCatalogVersion.get();
			if (cachedVersion == null || cachedVersion.isBlank())
			{
				cachedVersion = readDiskVersion();
			}
			LiveCardsResponse response = api.getLiveCards(cachedVersion);
			if (response.isNotModified())
			{
				if (cardDatabase.size() == 0)
				{
					loadDiskCacheIfPresent();
				}
				if (response.getCatalogVersion() != null && !response.getCatalogVersion().isBlank())
				{
					cachedCatalogVersion.set(response.getCatalogVersion());
				}
				log.debug("Live card catalog not modified (version={})", cachedCatalogVersion.get());
				notifyChanged();
				return;
			}
			JsonObject body = response.getBody();
			List<CardDefinition> parsed = LiveCardsCatalogParser.parse(body);
			if (parsed.isEmpty())
			{
				log.error("Live card catalog returned empty items/npcs; keeping previous");
				return;
			}
			String raw = response.getRawJson();
			if (raw != null && !raw.isBlank())
			{
				persistDiskCache(raw, response.getCatalogVersion());
			}
			if (response.getCatalogVersion() != null && !response.getCatalogVersion().isBlank())
			{
				cachedCatalogVersion.set(response.getCatalogVersion());
			}
			cardDatabase.replaceCards(parsed, "GET /api/v1/catalog/cards/live");
			notifyChanged();
		}
		catch (Exception ex)
		{
			if (ex instanceof CloudApiException && "consent_required".equals(((CloudApiException) ex).getCode()))
			{
				log.debug("Live card catalog skipped until cloud consent");
				return;
			}
			log.warn("Live card catalog fetch failed", ex);
		}
	}

	/** Best-effort removal of the obsolete card-art overlay cache files, ignoring failures. */
	private void deleteStaleCardArtCache()
	{
		Path dir = diskCacheDir();
		try
		{
			Files.deleteIfExists(dir.resolve(CARD_ART_CACHE_FILE));
			Files.deleteIfExists(dir.resolve(CARD_ART_VERSION_FILE));
		}
		catch (Exception ex)
		{
			log.debug("Failed deleting obsolete card-art overlay cache", ex);
		}
	}

	/** Parses a raw live-catalog JSON string (as stored on disk) into card definitions. */
	private static List<CardDefinition> parseLiveJson(String json)
	{
		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
		return LiveCardsCatalogParser.parse(obj);
	}

	/** Invokes the registered change listener, if any, swallowing its exceptions. */
	private void notifyChanged()
	{
		Runnable listener = changeListener.get();
		if (listener != null)
		{
			try
			{
				listener.run();
			}
			catch (Exception ex)
			{
				log.debug("Card catalog change listener failed", ex);
			}
		}
	}

	/** Writes the raw catalog JSON and version to disk atomically; failures are logged and ignored. */
	private void persistDiskCache(String json, String version)
	{
		Path dir = diskCacheDir();
		Path target = dir.resolve(LIVE_CACHE_FILE);
		try
		{
			AtomicFiles.writeString(target, json, StandardCharsets.UTF_8);
			if (version != null && !version.isBlank())
			{
				Files.writeString(dir.resolve(LIVE_VERSION_FILE), version.trim(), StandardCharsets.UTF_8);
			}
		}
		catch (Exception ex)
		{
			log.debug("Card catalog disk cache write failed", ex);
		}
	}

	/** Reads the last persisted catalog version from disk, or null if absent/unreadable. */
	private static String readDiskVersion()
	{
		Path file = diskCacheDir().resolve(LIVE_VERSION_FILE);
		if (!Files.isRegularFile(file))
		{
			return null;
		}
		try
		{
			String v = Files.readString(file, StandardCharsets.UTF_8).trim();
			return v.isEmpty() ? null : v;
		}
		catch (IOException ex)
		{
			return null;
		}
	}

	/** Directory under the RuneLite home folder used for the card catalog disk cache. */
	private static Path diskCacheDir()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "catalog");
	}

	/** Clears the cached catalog version and deletes the entire disk cache directory. */
	public void deleteDiskCache()
	{
		cachedCatalogVersion.set(null);
		deleteDirectoryQuietly(diskCacheDir());
	}

	/** Recursively deletes a directory, best-effort, logging but not throwing on failure. */
	private static void deleteDirectoryQuietly(Path dir)
	{
		if (dir == null || !Files.isDirectory(dir))
		{
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(dir))
		{
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(path ->
			{
				try
				{
					Files.deleteIfExists(path);
				}
				catch (Exception ex)
				{
					log.debug("Failed deleting catalog cache path {}", path, ex);
				}
			});
			log.info("Removed obsolete card catalog disk cache {}", dir);
		}
		catch (Exception ex)
		{
			log.debug("Failed walking catalog cache dir {}", dir, ex);
		}
	}
}

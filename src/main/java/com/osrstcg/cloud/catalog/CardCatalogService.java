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

	public void setChangeListener(Runnable listener)
	{
		changeListener.set(listener);
	}

	public void loadDiskCacheIfPresent()
	{
		deleteObsoleteCardArtOverlayCache();
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

	public CompletableFuture<Void> prefetchAsync()
	{
		return CompletableFuture.runAsync(this::fetchAndApply, scheduler);
	}

	public CompletableFuture<Void> refreshOnLogin()
	{
		if (!loginFetchAttempted.compareAndSet(false, true))
		{
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.runAsync(this::fetchAndApply, scheduler);
	}

	public void resetLoginFetchGate()
	{
		loginFetchAttempted.set(false);
	}

	public CompletableFuture<Void> refreshNow()
	{
		loginFetchAttempted.set(true);
		return CompletableFuture.runAsync(this::fetchAndApply, scheduler);
	}

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

	private void deleteObsoleteCardArtOverlayCache()
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

	private static List<CardDefinition> parseLiveJson(String json)
	{
		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
		return LiveCardsCatalogParser.parse(obj);
	}

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

	private static Path diskCacheDir()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "catalog");
	}

	public void deleteDiskCache()
	{
		cachedCatalogVersion.set(null);
		deleteDirectoryQuietly(diskCacheDir());
	}

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

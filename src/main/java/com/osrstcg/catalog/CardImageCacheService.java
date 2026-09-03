package com.osrstcg.catalog;

import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.cloud.session.CloudTokenStore;
import com.osrstcg.persist.TcgStateHash;
import com.osrstcg.util.AtomicFiles;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Loads and caches card/pack artwork fetched over HTTP. Keeps a bounded in-memory LRU of decoded
 * images plus an on-disk cache keyed by a normalized URL identity, deduplicates concurrent fetches
 * of the same image, and cools down repeated failures for a URL instead of retrying every call.
 */
@Slf4j
@Singleton
public class CardImageCacheService
{
	private static final String USER_AGENT =
		"osrs-tcg (https://github.com/Azderi/osrs-tcg)";
	private static final int MEMORY_CACHE_MAX_ENTRIES = 256;
	private static final int MAX_IN_FLIGHT_LOADS = 4;
	private static final AtomicInteger IMAGE_LOADER_SEQ = new AtomicInteger();
	private static final ThreadFactory IMAGE_LOADER_THREAD_FACTORY = r ->
	{
		Thread t = new Thread(r, "osrs-tcg-card-image-" + IMAGE_LOADER_SEQ.incrementAndGet());
		t.setDaemon(true);
		return t;
	};

	private final OkHttpClient okHttpClient;
	private final CloudTokenStore tokenStore;
	private final Semaphore loadPermits = new Semaphore(MAX_IN_FLIGHT_LOADS);
	private final ExecutorService imageLoadExecutor = Executors.newFixedThreadPool(
		MAX_IN_FLIGHT_LOADS, IMAGE_LOADER_THREAD_FACTORY);
	private final Map<String, BufferedImage> memoryCache = Collections.synchronizedMap(
		new LinkedHashMap<String, BufferedImage>(MEMORY_CACHE_MAX_ENTRIES + 1, 0.75f, true)
		{
			/** Evicts the least-recently-used entry once the cache exceeds {@link #MEMORY_CACHE_MAX_ENTRIES}. */
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
			{
				return size() > MEMORY_CACHE_MAX_ENTRIES;
			}
		});
	private final Map<String, CompletableFuture<BufferedImage>> loadingFutures = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> failedAtMs = new ConcurrentHashMap<>();
	private static final long FAIL_COOLDOWN_MS = 60_000L;
	private static final long PACK_FAIL_COOLDOWN_MS = 5_000L;

	/** Wires the HTTP client used to fetch images and the token store used to gate osrs-tcg.net requests. */
	@Inject
	public CardImageCacheService(OkHttpClient okHttpClient, CloudTokenStore tokenStore)
	{
		this.okHttpClient = okHttpClient;
		this.tokenStore = tokenStore;
	}

	/** Kicks off background loads for every non-blank URL and returns a future that completes once they've all settled. */
	public CompletableFuture<Void> preloadAsync(Collection<String> urls)
	{
		if (urls == null)
		{
			return CompletableFuture.completedFuture(null);
		}
		List<CompletableFuture<BufferedImage>> futures = urls.stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(url -> !url.isEmpty())
			.map(this::ensureLoad)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
		if (futures.isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
	}

	/**
	 * Non-blocking lookup: returns the decoded image if it's already in the memory cache, otherwise
	 * kicks off a background load (unless the URL is in its failure cooldown) and returns {@code null}.
	 */
	public BufferedImage getCached(String pathOrUrl)
	{
		String fetchUrl = resolveFetchUrl(pathOrUrl);
		if (fetchUrl == null)
		{
			return null;
		}

		String cacheKey = ImageCacheIdentity.cacheIdentity(fetchUrl);
		BufferedImage cached = memoryCache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}

		if (!isInFailCooldown(cacheKey, fetchUrl))
		{
			ensureLoad(pathOrUrl);
		}
		return null;
	}

	/**
	 * Returns the in-flight or newly-started load future for {@code rawUrl}, or {@code null} if the
	 * URL can't be resolved, is already cached, or is in its failure cooldown. Loads run on
	 * {@link #imageLoadExecutor} gated by {@link #loadPermits}; on completion the result is written
	 * to {@link #memoryCache} or {@link #failedAtMs} as appropriate.
	 */
	private CompletableFuture<BufferedImage> ensureLoad(String rawUrl)
	{
		String fetchUrl = resolveFetchUrl(rawUrl);
		if (fetchUrl == null)
		{
			return null;
		}
		String cacheKey = ImageCacheIdentity.cacheIdentity(fetchUrl);
		BufferedImage cached = memoryCache.get(cacheKey);
		if (cached != null)
		{
			return CompletableFuture.completedFuture(cached);
		}
		if (isInFailCooldown(cacheKey, fetchUrl))
		{
			return null;
		}
		CompletableFuture<BufferedImage> inFlight = loadingFutures.get(cacheKey);
		if (inFlight != null)
		{
			return inFlight;
		}

		return loadingFutures.computeIfAbsent(cacheKey, key -> CompletableFuture
			.supplyAsync(() ->
			{
				loadPermits.acquireUninterruptibly();
				try
				{
					return loadImage(fetchUrl, key);
				}
				finally
				{
					loadPermits.release();
				}
			}, imageLoadExecutor)
			.whenComplete((image, ex) ->
			{
				if (image != null)
				{
					failedAtMs.remove(key);
					memoryCache.put(key, image);
				}
				else if (!ImageCacheIdentity.isEphemeralAuthUrl(fetchUrl))
				{
					failedAtMs.put(key, System.currentTimeMillis());
				}
				loadingFutures.remove(key);
			}));
	}

	/** Whether {@code cacheKey} failed recently enough to still be within its cooldown window (pack/card-back URLs use a shorter one); clears expired entries. */
	private boolean isInFailCooldown(String cacheKey, String fetchUrl)
	{
		Long failedAt = failedAtMs.get(cacheKey);
		if (failedAt == null)
		{
			return false;
		}
		long cooldown = ImageCacheIdentity.isShortFailCooldownUrl(fetchUrl)
			? PACK_FAIL_COOLDOWN_MS
			: FAIL_COOLDOWN_MS;
		if (System.currentTimeMillis() - failedAt >= cooldown)
		{
			failedAtMs.remove(cacheKey, failedAt);
			return false;
		}
		return true;
	}

	/**
	 * Loads one image: tries disk cache first, then (for {@code https://} URLs, and gated by cloud
	 * consent for osrs-tcg.net hosts) fetches over HTTP, persisting the bytes to disk and re-reading
	 * via {@link #tryLoadFromDisk} so the result is subsampled the same way either path takes.
	 * Returns {@code null} on any failure.
	 */
	private BufferedImage loadImage(String fetchUrl, String cacheKey)
	{
		BufferedImage fromDisk = tryLoadFromDisk(cacheKey, fetchUrl);
		if (fromDisk != null)
		{
			return fromDisk;
		}

		if (fetchUrl.isEmpty() || !fetchUrl.startsWith("https://"))
		{
			return null;
		}

		// No network to osrs-tcg.net until the user accepts cloud consent.
		if (ImageCacheIdentity.isOsrsTcgNetUrl(fetchUrl) && !tokenStore.isMigrated())
		{
			return null;
		}

		try
		{
			Request request = new Request.Builder()
				.url(fetchUrl)
				.header("User-Agent", USER_AGENT)
				.build();
			try (Response response = okHttpClient.newCall(request).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					if (ImageCacheIdentity.isPackAssetUrl(fetchUrl))
					{
						log.warn("Pack image HTTP {} for {}", response.code(), fetchUrl);
					}
					else
					{
						log.debug("Card image HTTP {} for {}", response.code(), fetchUrl);
					}
					return null;
				}
				byte[] bytes = response.body().bytes();
				if (bytes.length == 0)
				{
					return null;
				}
				persistBytesToDisk(cacheKey, bytes);
				BufferedImage fromCache = tryLoadFromDisk(cacheKey, fetchUrl);
				if (fromCache != null)
				{
					return fromCache;
				}
				BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
				if (image == null)
				{
					return null;
				}
				return downscaleForMemoryCache(image, ImageCacheIdentity.maxMemoryEdgeForUrl(fetchUrl));
			}
		}
		catch (Exception ex)
		{
			log.debug("Failed to cache card image {}", fetchUrl, ex);
		}
		return null;
	}

	/** Bilinearly downscales {@code source} so its longer edge is at most {@code maxEdgePx}; returns it unchanged if already within the cap. */
	private static BufferedImage downscaleForMemoryCache(BufferedImage source, int maxEdgePx)
	{
		if (source == null)
		{
			return null;
		}
		int cap = Math.max(1, maxEdgePx);
		int maxEdge = Math.max(source.getWidth(), source.getHeight());
		if (maxEdge <= cap)
		{
			return source;
		}
		double scale = cap / (double) maxEdge;
		int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
		BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = scaled.createGraphics();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.drawImage(source, 0, 0, w, h, null);
		}
		finally
		{
			g2.dispose();
		}
		return scaled;
	}

	/** Deletes disk cache directories from prior cache-format versions, freeing space left behind by upgrades. */
	public void deleteObsoleteImageCacheDirs()
	{
		Path root = tcgCacheRoot();
		deleteDirectoryQuietly(root.resolve("images-v3"));
		deleteDirectoryQuietly(root.resolve("images-v2"));
		deleteDirectoryQuietly(root.resolve("images"));
	}

	/** Recursively deletes {@code dir} if it exists, logging but not throwing on failure. */
	private static void deleteDirectoryQuietly(Path dir)
	{
		if (dir == null || !Files.isDirectory(dir))
		{
			return;
		}
		try (Stream<Path> walk = Files.walk(dir))
		{
			walk.sorted(Comparator.reverseOrder()).forEach(path ->
			{
				try
				{
					Files.deleteIfExists(path);
				}
				catch (Exception ex)
				{
					log.debug("Failed deleting image cache path {}", path, ex);
				}
			});
			log.info("Removed obsolete image cache {}", dir);
		}
		catch (Exception ex)
		{
			log.debug("Failed walking image cache dir {}", dir, ex);
		}
	}

	/** Disk cache path for {@code cacheKey}: a name derived from the pack/card-back URL for pack assets, else a hashed filename under the general cache dir. */
	private Path diskCacheFile(String cacheKey)
	{
		String packName = ImageCacheIdentity.packDiskFileName(cacheKey);
		if (packName != null)
		{
			return packDiskCacheDir().resolve(packName);
		}
		return diskCacheDir().resolve(TcgStateHash.hexOfUtf8(cacheKey) + ".png");
	}

	/**
	 * Reads and decodes the disk-cached image for {@code cacheKey}, if present and readable, subsampling
	 * during decode so the result is already near the target memory-cache size (deletes the file and
	 * returns {@code null} if it can't be decoded as an image).
	 */
	private BufferedImage tryLoadFromDisk(String cacheKey, String edgeHintUrl)
	{
		Path file = diskCacheFile(cacheKey);
		if (!Files.isRegularFile(file))
		{
			return null;
		}
		try (InputStream in = Files.newInputStream(file);
			ImageInputStream imageStream = ImageIO.createImageInputStream(in))
		{
			if (imageStream == null)
			{
				return null;
			}
			var readers = ImageIO.getImageReaders(imageStream);
			if (!readers.hasNext())
			{
				Files.deleteIfExists(file);
				return null;
			}
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(imageStream, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				int maxEdge = Math.max(width, height);
				int memoryCap = ImageCacheIdentity.maxMemoryEdgeForUrl(
					edgeHintUrl != null ? edgeHintUrl : cacheKey);
				int subsample = 1;
				while (subsample < 32 && maxEdge / subsample > memoryCap * 2)
				{
					subsample *= 2;
				}
				ImageReadParam param = reader.getDefaultReadParam();
				if (subsample > 1)
				{
					param.setSourceSubsampling(subsample, subsample, 0, 0);
				}
				BufferedImage image = reader.read(0, param);
				if (image == null)
				{
					Files.deleteIfExists(file);
					return null;
				}
				return downscaleForMemoryCache(image, memoryCap);
			}
			finally
			{
				reader.dispose();
			}
		}
		catch (Exception ex)
		{
			log.debug("Disk cache read failed for {}", file, ex);
			return null;
		}
	}

	/** Writes {@code bytes} to the disk cache file for {@code cacheKey}, atomically; no-ops on empty input and logs (not throws) on write failure. */
	private void persistBytesToDisk(String cacheKey, byte[] bytes)
	{
		if (bytes == null || bytes.length == 0)
		{
			return;
		}
		Path target = diskCacheFile(cacheKey);
		try
		{
			AtomicFiles.writeBytes(target, bytes);
		}
		catch (Exception ex)
		{
			log.debug("Disk cache write failed for {}", target, ex);
		}
	}

	/** Resolves {@code rawUrl} to an absolute fetchable URL via {@link CloudEndpoints#resolvePublicUrl}, or {@code null} if it can't be resolved. */
	private static String resolveFetchUrl(String rawUrl)
	{
		if (rawUrl == null)
		{
			return null;
		}
		String fetchUrl = CloudEndpoints.resolvePublicUrl(rawUrl);
		if (fetchUrl.isEmpty())
		{
			return null;
		}
		return fetchUrl;
	}

	/** The plugin's cache root directory under the RuneLite data dir. */
	private static Path tcgCacheRoot()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG");
	}

	/** Disk cache directory for general card images (current format version). */
	private Path diskCacheDir()
	{
		return tcgCacheRoot().resolve("images-v4");
	}

	/** Disk cache directory for pack sleeve/card-back assets, kept separate since they're keyed by filename rather than hash. */
	private Path packDiskCacheDir()
	{
		return tcgCacheRoot().resolve("packs");
	}
}

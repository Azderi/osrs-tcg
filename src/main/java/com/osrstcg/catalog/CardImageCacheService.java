package com.osrstcg.catalog;

import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.cloud.session.CloudTokenStore;
import com.osrstcg.persist.TcgStateHash;
import com.osrstcg.util.AtomicFiles;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
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
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Loads and caches card/pack artwork from the production web/API hosts ({@link CloudEndpoints}).
 * Relative paths are joined to the web base;
 * {@code /api/...} artwork paths use the API host.
 * <p>
 * Disk layout under {@code ~/.runelite/OSRS-TCG/}:
 * <ul>
 *   <li>{@code images-v4/} - card detail / foil / artwork (SHA-256 of stable URL identity)</li>
 *   <li>{@code packs/} - pack {@code image}/{@code thumbnail} assets by basename, plus
 *       pack-reveal {@code cardback.png}</li>
 * </ul>
 * Memory holds a downscaled decode; disk keeps the original download bytes.
 * <p>
 * Signed artwork URLs ({@code /artwork/files/:id?token=…}) rotate {@code token} on every pack
 * open. Cache keys strip that query so the same ULID hits disk/memory across pulls while HTTP
 * still uses the fresh signed URL.
 */
@Slf4j
@Singleton
public class CardImageCacheService
{
	private static final String USER_AGENT =
		"osrs-tcg (https://github.com/Azderi/osrs-tcg)";
	/** Max decoded images kept in heap; evicted entries remain on disk. */
	private static final int MEMORY_CACHE_MAX_ENTRIES = 256;
	/**
	 * Longest edge kept in the memory cache for banded detail icons. Cards are drawn ~100–180px
	 * wide; full detail PNGs in the disk cache otherwise cause large GC pauses while decoding.
	 */
	private static final int MAX_MEMORY_IMAGE_EDGE_PX = 130;
	/** Foil bleed faces need higher fidelity when cover-cropped into the inner well. */
	private static final int MAX_MEMORY_FULL_ART_EDGE_PX = 520;
	/**
	 * Sealed pack sleeves on the reveal overlay are ~400×545 design (up to 2× zoom). Keep enough
	 * resolution that {@code image} does not look like the shop {@code thumbnail}.
	 */
	private static final int MAX_MEMORY_PACK_SLEEVE_EDGE_PX = 1100;
	/** Cap concurrent disk/network decodes. */
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
	/** Dedicated pool for blocking ImageIO/HTTP. */
	private final ExecutorService imageLoadExecutor = Executors.newFixedThreadPool(
		MAX_IN_FLIGHT_LOADS, IMAGE_LOADER_THREAD_FACTORY);
	/** Memory keys are {@link #cacheIdentity(String)}, not the raw signed fetch URL. */
	private final Map<String, BufferedImage> memoryCache = Collections.synchronizedMap(
		new LinkedHashMap<String, BufferedImage>(MEMORY_CACHE_MAX_ENTRIES + 1, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
			{
				return size() > MEMORY_CACHE_MAX_ENTRIES;
			}
		});
	private final Map<String, CompletableFuture<BufferedImage>> loadingFutures = new ConcurrentHashMap<>();
	/**
	 * Cache identities that recently failed to load, with failure timestamp.
	 * Used so paint paths skip immediate re-fetch; entries expire so transient CDN/deploy
	 * 404s (e.g. pack thumbnails referenced before assets are published) can recover.
	 */
	private final ConcurrentHashMap<String, Long> failedAtMs = new ConcurrentHashMap<>();
	/** Default cooldown after a failed card/detail image load. */
	private static final long FAIL_COOLDOWN_MS = 60_000L;
	/**
	 * Pack sleeve/thumbnail assets are tiny and often race deploys; retry sooner so the shop
	 * recovers without requiring a full client restart.
	 */
	private static final long PACK_FAIL_COOLDOWN_MS = 5_000L;

	@Inject
	public CardImageCacheService(OkHttpClient okHttpClient, CloudTokenStore tokenStore)
	{
		this.okHttpClient = okHttpClient;
		this.tokenStore = tokenStore;
	}

	public void preload(Collection<String> urls)
	{
		preloadAsync(urls);
	}

	/**
	 * Starts background loads for each URL and completes when all have settled (cached or failed).
	 * Safe to ignore the returned future when only warming the cache.
	 */
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
			.collect(java.util.stream.Collectors.toList());
		if (futures.isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
	}

	/** Absolute URL suitable for external embeds. */
	public String resolveAbsoluteUrl(String pathOrUrl)
	{
		return normalizeUrl(pathOrUrl);
	}

	/**
	 * Returns a cached image if present. Safe to call from overlay/UI paint paths:
	 * only reads the memory cache and may kick off a background load - never blocks
	 * on network/disk and never writes the cache on this thread.
	 */
	public BufferedImage getCached(String pathOrUrl)
	{
		if (pathOrUrl == null)
		{
			return null;
		}

		String fetchUrl = normalizeUrl(pathOrUrl);
		if (fetchUrl.isEmpty())
		{
			return null;
		}

		String cacheKey = cacheIdentity(fetchUrl);
		BufferedImage cached = memoryCache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}

		if (!isInFailCooldown(cacheKey, fetchUrl))
		{
			ensureLoad(fetchUrl);
		}
		return null;
	}

	/**
	 * Starts (or joins) a background load. Returns an already-completed future when the image is
	 * cached, {@code null} when the URL is empty / in fail cooldown, otherwise the in-flight future.
	 */
	private CompletableFuture<BufferedImage> ensureLoad(String rawUrl)
	{
		String fetchUrl = normalizeUrl(rawUrl);
		if (fetchUrl.isEmpty())
		{
			return null;
		}
		String cacheKey = cacheIdentity(fetchUrl);
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
				// Populate cache before removing the in-flight future so paint reads never
				// observe "not loading" and "not cached" at the same time.
				if (image != null)
				{
					failedAtMs.remove(key);
					memoryCache.put(key, image);
				}
				else if (!isEphemeralAuthUrl(fetchUrl))
				{
					// Signed artwork tokens rotate; do not blacklist the stable art id forever
					// after one expired-token miss. Other assets use a timed cooldown so
					// transient 404s (CDN race on newly published pack art) can recover.
					failedAtMs.put(key, System.currentTimeMillis());
				}
				loadingFutures.remove(key);
			}));
	}

	private boolean isInFailCooldown(String cacheKey, String fetchUrl)
	{
		Long failedAt = failedAtMs.get(cacheKey);
		if (failedAt == null)
		{
			return false;
		}
		long cooldown = isPackAssetUrl(fetchUrl) || isCardBackUrl(fetchUrl)
			? PACK_FAIL_COOLDOWN_MS
			: FAIL_COOLDOWN_MS;
		if (System.currentTimeMillis() - failedAt >= cooldown)
		{
			failedAtMs.remove(cacheKey, failedAt);
			return false;
		}
		return true;
	}

	private BufferedImage loadImage(String fetchUrl, String cacheKey)
	{
		BufferedImage fromDisk = tryLoadFromDisk(cacheKey, fetchUrl);
		if (fromDisk != null)
		{
			return fromDisk;
		}

		if (fetchUrl.isEmpty() || !(fetchUrl.startsWith("http://") || fetchUrl.startsWith("https://")))
		{
			return null;
		}

		// No network to osrs-tcg.net until the user accepts cloud consent.
		if (isOsrsTcgNetUrl(fetchUrl) && !tokenStore.isMigrated())
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
					if (isPackAssetUrl(fetchUrl))
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
				// Persist under stable cache identity; fetch URL may include a rotating token.
				persistBytesToDisk(cacheKey, bytes);
				BufferedImage fromCache = tryLoadFromDisk(cacheKey, fetchUrl);
				if (fromCache != null)
				{
					return fromCache;
				}
				BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
				if (image == null)
				{
					return null;
				}
				return downscaleForMemoryCache(image, maxMemoryEdgeForUrl(fetchUrl));
			}
		}
		catch (Exception ex)
		{
			log.debug("Failed to cache card image {}", fetchUrl, ex);
		}
		return null;
	}

	/**
	 * Keeps heap pressure low when the disk/network asset is a full-size detail PNG.
	 * Disk cache retains the original; only the in-memory copy is scaled.
	 */
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

	static int maxMemoryEdgeForUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return MAX_MEMORY_IMAGE_EDGE_PX;
		}
		String lower = url.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("/images/cardback"))
		{
			return MAX_MEMORY_FULL_ART_EDGE_PX;
		}
		if (lower.contains("/images/packs/"))
		{
			// Shop icons stay small; reveal sleeves use the full pack {@code image} asset.
			if (lower.contains("thumbnail"))
			{
				return MAX_MEMORY_IMAGE_EDGE_PX;
			}
			return MAX_MEMORY_PACK_SLEEVE_EDGE_PX;
		}
		if (lower.contains("/artwork/files/") || lower.contains("/foil/"))
		{
			return MAX_MEMORY_FULL_ART_EDGE_PX;
		}
		return MAX_MEMORY_IMAGE_EDGE_PX;
	}

	private Path diskCacheDir()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "images-v4");
	}

	/** Pack sleeve + thumbnail downloads: {@code ~/.runelite/OSRS-TCG/packs/}. */
	private Path packDiskCacheDir()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "packs");
	}

	/**
	 * Drops superseded image cache trees left behind by older plugin versions
	 * ({@code images-v3}, {@code images-v2}, and {@code images} if present). Active caches are
	 * {@code images-v4} and {@code packs/}.
	 */
	public void deleteObsoleteImageCacheDirs()
	{
		Path root = Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG");
		deleteDirectoryQuietly(root.resolve("images-v3"));
		deleteDirectoryQuietly(root.resolve("images-v2"));
		deleteDirectoryQuietly(root.resolve("images"));
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

	private Path diskCacheFile(String cacheKey)
	{
		String packName = packDiskFileName(cacheKey);
		if (packName != null)
		{
			return packDiskCacheDir().resolve(packName);
		}
		return diskCacheDir().resolve(TcgStateHash.hexOfUtf8(cacheKey) + ".png");
	}

	/** {@code true} when the absolute URL is a pack catalog sleeve or thumbnail. */
	static boolean isPackAssetUrl(String normalizedUrl)
	{
		return normalizedUrl != null
			&& normalizedUrl.toLowerCase(java.util.Locale.ROOT).contains("/images/packs/");
	}

	/** Website card-back PNG used during pack reveal. */
	static boolean isCardBackUrl(String normalizedUrl)
	{
		return normalizedUrl != null
			&& normalizedUrl.toLowerCase(java.util.Locale.ROOT).contains("/images/cardback");
	}

	/**
	 * Safe basename for {@code OSRS-TCG/packs/}, or {@code null} when {@code url} is not a pack
	 * sleeve/thumbnail or the pack-reveal card back.
	 */
	static String packDiskFileName(String normalizedUrl)
	{
		if (isCardBackUrl(normalizedUrl))
		{
			return "cardback.png";
		}
		if (!isPackAssetUrl(normalizedUrl))
		{
			return null;
		}
		String path = normalizedUrl;
		int q = path.indexOf('?');
		if (q >= 0)
		{
			path = path.substring(0, q);
		}
		int slash = path.lastIndexOf('/');
		String raw = slash >= 0 ? path.substring(slash + 1) : path;
		if (raw.isBlank())
		{
			return TcgStateHash.hexOfUtf8(normalizedUrl) + ".bin";
		}
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++)
		{
			char c = raw.charAt(i);
			if ((c >= 'a' && c <= 'z')
				|| (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9')
				|| c == '.' || c == '_' || c == '-')
			{
				sb.append(c);
			}
			else
			{
				sb.append('_');
			}
		}
		String cleaned = sb.toString();
		if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals(".."))
		{
			return TcgStateHash.hexOfUtf8(normalizedUrl) + ".bin";
		}
		return cleaned;
	}

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
				int memoryCap = maxMemoryEdgeForUrl(edgeHintUrl != null ? edgeHintUrl : cacheKey);
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

	/** Writes original download bytes (pack assets → {@code packs/}, others → {@code images-v4/}). */
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

	/**
	 * Stable cache identity for an absolute URL. Strips rotating signed {@code token} query
	 * params (and any query on {@code /artwork/files/}) so the same artwork ULID maps to one
	 * disk/memory entry across pack opens.
	 */
	static String cacheIdentity(String absoluteUrl)
	{
		return ImageCacheIdentity.cacheIdentity(absoluteUrl);
	}

	/** {@code true} when HTTP auth is ephemeral (fresh {@code token} on each pack payload). */
	static boolean isEphemeralAuthUrl(String absoluteUrl)
	{
		return ImageCacheIdentity.isEphemeralAuthUrl(absoluteUrl);
	}

	/**
	 * Removes a single query parameter (case-insensitive name) while preserving other params.
	 */
	static String stripQueryParam(String absoluteUrl, String paramName)
	{
		return ImageCacheIdentity.stripQueryParam(absoluteUrl, paramName);
	}

	private String normalizeUrl(String pathOrUrl)
	{
		if (pathOrUrl == null)
		{
			return "";
		}
		String raw = pathOrUrl.trim();
		if (raw.isEmpty())
		{
			return "";
		}
		if (raw.startsWith("http://") || raw.startsWith("https://"))
		{
			return raw;
		}
		if (raw.startsWith("//"))
		{
			return "https:" + raw;
		}
		// Accepted artist uploads live on the API host; static /images live on the web host.
		if (raw.startsWith("/api/"))
		{
			return CloudEndpoints.apiUrl(raw);
		}
		return CloudEndpoints.webUrl(raw);
	}

	/** True when the URL host is {@code osrs-tcg.net} or a subdomain (e.g. {@code api.}). */
	static boolean isOsrsTcgNetUrl(String url)
	{
		HttpUrl parsed = HttpUrl.parse(url);
		if (parsed == null)
		{
			return false;
		}
		String host = parsed.host();
		if (host == null || host.isEmpty())
		{
			return false;
		}
		String lower = host.toLowerCase(java.util.Locale.ROOT);
		return "osrs-tcg.net".equals(lower) || lower.endsWith(".osrs-tcg.net");
	}
}

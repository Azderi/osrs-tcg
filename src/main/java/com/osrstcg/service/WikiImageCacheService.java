package com.osrstcg.service;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
@Singleton
public class WikiImageCacheService
{
	private static final String WIKI_BASE_URL = "https://oldschool.runescape.wiki";
	/**
	 * Identify the plugin clearly. Fake browser UAs like {@code Mozilla/5.0 (osrstcg)} are
	 * challenged by Cloudflare; a descriptive client string is allowed on /images/.
	 */
	private static final String USER_AGENT =
		"osrs-tcg (https://github.com/Azderi/osrs-tcg)";
	/** Max decoded images kept in heap; evicted entries remain on disk. */
	private static final int MEMORY_CACHE_MAX_ENTRIES = 128;
	/** Cap concurrent disk/network decodes so fast album paging cannot flood the common pool. */
	private static final int MAX_IN_FLIGHT_LOADS = 12;

	private final OkHttpClient okHttpClient;
	private final Semaphore loadPermits = new Semaphore(MAX_IN_FLIGHT_LOADS);
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
	/** URLs that failed to load; skip re-fetching on the overlay/album paint path. */
	private final Set<String> failedUrls = ConcurrentHashMap.newKeySet();

	@Inject
	public WikiImageCacheService(OkHttpClient okHttpClient)
	{
		this.okHttpClient = okHttpClient;
	}

	public void preload(Collection<String> urls)
	{
		if (urls == null)
		{
			return;
		}

		urls.stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(url -> !url.isEmpty())
			.forEach(this::ensureLoad);
	}

	/** True when the image is not yet available in memory (not started or still loading). */
	public boolean needsLoad(String url)
	{
		if (url == null)
		{
			return false;
		}

		String normalized = normalizeUrl(url);
		if (normalized.isEmpty() || memoryCache.containsKey(normalized) || failedUrls.contains(normalized))
		{
			return false;
		}

		CompletableFuture<BufferedImage> future = loadingFutures.get(normalized);
		return future == null || !future.isDone();
	}

	/** Wiki URL suitable for external embeds (e.g. Dink Discord thumbnail). */
	public String publicImageUrl(String rawUrl)
	{
		String normalized = normalizeUrl(rawUrl);
		if (normalized.isEmpty())
		{
			return "";
		}
		String fromThumb = extractFilenameFromThumbPath(rawUrl);
		if (!fromThumb.isEmpty())
		{
			return directImageUrl(fromThumb);
		}
		String fromPath = extractFilenameFromPath(normalized);
		if (!fromPath.isEmpty() && !looksLikeThumbSizeSegment(fromPath))
		{
			return directImageUrl(fromPath);
		}
		return normalized;
	}

	/**
	 * Returns a cached image if present. Safe to call from overlay/UI paint paths:
	 * only reads the memory cache and may kick off a background load — never blocks
	 * on network/disk and never writes the cache on this thread.
	 */
	public BufferedImage getCached(String url)
	{
		if (url == null)
		{
			return null;
		}

		String normalized = normalizeUrl(url);
		if (normalized.isEmpty())
		{
			return null;
		}

		BufferedImage cached = memoryCache.get(normalized);
		if (cached != null)
		{
			return cached;
		}

		if (!failedUrls.contains(normalized))
		{
			ensureLoad(normalized);
		}
		return null;
	}

	private void ensureLoad(String rawUrl)
	{
		String url = normalizeUrl(rawUrl);
		if (url.isEmpty()
			|| memoryCache.containsKey(url)
			|| failedUrls.contains(url)
			|| loadingFutures.containsKey(url))
		{
			return;
		}

		loadingFutures.computeIfAbsent(url, key -> CompletableFuture
			.supplyAsync(() ->
			{
				loadPermits.acquireUninterruptibly();
				try
				{
					return loadImage(key);
				}
				finally
				{
					loadPermits.release();
				}
			})
			.whenComplete((image, ex) ->
			{
				// Populate cache before removing the in-flight future so paint reads never
				// observe "not loading" and "not cached" at the same time.
				if (image != null)
				{
					failedUrls.remove(key);
					memoryCache.put(key, image);
				}
				else
				{
					failedUrls.add(key);
				}
				loadingFutures.remove(key);
			}));
	}

	private BufferedImage loadImage(String url)
	{
		BufferedImage fromDisk = tryLoadFromDisk(url);
		if (fromDisk != null)
		{
			return fromDisk;
		}

		List<String> candidates = buildCandidateUrls(url);
		if (candidates.isEmpty())
		{
			return null;
		}

		for (String candidate : candidates)
		{
			try
			{
				Request request = new Request.Builder()
					.url(candidate)
					.header("User-Agent", USER_AGENT)
					.build();
				try (Response response = okHttpClient.newCall(request).execute())
				{
					if (!response.isSuccessful() || response.body() == null)
					{
						log.debug("Wiki image HTTP {} for {}", response.code(), candidate);
						continue;
					}
					try (InputStream inputStream = response.body().byteStream())
					{
						BufferedImage image = ImageIO.read(inputStream);
						if (image != null)
						{
							persistToDisk(url, image);
							return image;
						}
					}
				}
			}
			catch (Exception ex)
			{
				log.debug("Failed to cache image candidate {}", candidate, ex);
			}
		}
		return null;
	}

	private Path diskCacheDir()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "images");
	}

	private Path diskCacheFile(String normalizedUrl)
	{
		return diskCacheDir().resolve(sha256Hex(normalizedUrl) + ".png");
	}

	private BufferedImage tryLoadFromDisk(String normalizedUrl)
	{
		Path file = diskCacheFile(normalizedUrl);
		if (!Files.isRegularFile(file))
		{
			return null;
		}
		try (InputStream in = Files.newInputStream(file))
		{
			BufferedImage image = ImageIO.read(in);
			if (image == null)
			{
				Files.deleteIfExists(file);
			}
			return image;
		}
		catch (Exception ex)
		{
			log.debug("Disk cache read failed for {}", file, ex);
			return null;
		}
	}

	private void persistToDisk(String normalizedUrl, BufferedImage image)
	{
		if (image == null)
		{
			return;
		}
		Path dir = diskCacheDir();
		Path target = diskCacheFile(normalizedUrl);
		Path tmp = dir.resolve(target.getFileName().toString() + ".tmp");
		try
		{
			Files.createDirectories(dir);
			try (OutputStream out = Files.newOutputStream(tmp))
			{
				if (!ImageIO.write(image, "png", out))
				{
					log.debug("ImageIO.write returned false for disk cache {}", target);
					Files.deleteIfExists(tmp);
					return;
				}
			}
			try
			{
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex)
			{
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (Exception ex)
		{
			log.debug("Disk cache write failed for {}", target, ex);
			try
			{
				Files.deleteIfExists(tmp);
			}
			catch (Exception ignore)
			{
				// ignore
			}
		}
	}

	private static String sha256Hex(String value)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
			char[] hex = "0123456789abcdef".toCharArray();
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				sb.append(hex[(b >> 4) & 0xF]).append(hex[b & 0xF]);
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private List<String> buildCandidateUrls(String rawUrl)
	{
		String normalized = normalizeUrl(rawUrl);
		if (normalized.isEmpty())
		{
			return List.of();
		}

		List<String> candidates = new ArrayList<>();

		// Prefer direct /images/[name].png (GCS). Avoid Special:FilePath ÔÇö that hits MediaWiki/Cloudflare.
		String fromThumb = extractFilenameFromThumbPath(rawUrl);
		if (!fromThumb.isEmpty())
		{
			addUnique(candidates, directImageUrl(fromThumb));
			addPotionDoseFallbacks(candidates, fromThumb);
		}

		// Original card URL (often a thumb); also served from GCS.
		addUnique(candidates, normalized);

		// Generic fallback from final path segment (skip MediaWiki thumb size names).
		String fromPath = extractFilenameFromPath(normalized);
		if (!fromPath.isEmpty() && !looksLikeThumbSizeSegment(fromPath))
		{
			addUnique(candidates, directImageUrl(fromPath));
			addPotionDoseFallbacks(candidates, fromPath);
		}
		return candidates;
	}

	private void addPotionDoseFallbacks(List<String> candidates, String filename)
	{
		if (filename == null || filename.isEmpty())
		{
			return;
		}

		// Many potion assets are dose-specific on wiki (e.g. Antifire_potion(4)_detail.png).
		if (filename.endsWith("_potion_detail.png") && !filename.contains("(4)"))
		{
			String fourDose = filename.replace("_potion_detail.png", "_potion(4)_detail.png");
			addUnique(candidates, directImageUrl(fourDose));
		}

		if (filename.endsWith("_mix_detail.png") && !filename.contains("(2)"))
		{
			String twoDose = filename.replace("_mix_detail.png", "_mix(2)_detail.png");
			addUnique(candidates, directImageUrl(twoDose));
		}
	}

	private static void addUnique(List<String> candidates, String url)
	{
		if (url != null && !url.isEmpty() && !candidates.contains(url))
		{
			candidates.add(url);
		}
	}

	/**
	 * Direct wiki image URL served from Google Cloud Storage (not MediaWiki).
	 * e.g. https://oldschool.runescape.wiki/images/Abyssal_whip_detail.png
	 */
	private String directImageUrl(String filename)
	{
		String safe = filename == null ? "" : filename.trim();
		if (safe.isEmpty())
		{
			return "";
		}
		// Keep wiki-safe URL encoding for parenthesized dose variants.
		safe = safe.replace("(", "%28").replace(")", "%29");
		return WIKI_BASE_URL + "/images/" + safe;
	}

	/** True for MediaWiki thumb basename segments like {@code 130px-Foo_detail.png}. */
	private static boolean looksLikeThumbSizeSegment(String segment)
	{
		return segment != null && segment.matches("\\d+px-.+");
	}

	private String extractFilenameFromThumbPath(String rawUrl)
	{
		if (rawUrl == null)
		{
			return "";
		}
		String value = rawUrl.trim();
		String marker = "/images/thumb/";
		int markerIndex = value.indexOf(marker);
		if (markerIndex < 0)
		{
			return "";
		}

		String tail = value.substring(markerIndex + marker.length());
		int slash = tail.indexOf('/');
		if (slash <= 0)
		{
			return "";
		}
		return tail.substring(0, slash);
	}

	private String extractFilenameFromPath(String normalizedUrl)
	{
		if (normalizedUrl == null)
		{
			return "";
		}
		int lastSlash = normalizedUrl.lastIndexOf('/');
		if (lastSlash < 0 || lastSlash >= normalizedUrl.length() - 1)
		{
			return "";
		}
		String segment = normalizedUrl.substring(lastSlash + 1).trim();
		return segment.isEmpty() ? "" : segment;
	}

	private String normalizeUrl(String rawUrl)
	{
		if (rawUrl == null)
		{
			return "";
		}

		String url = rawUrl.trim();
		if (url.isEmpty())
		{
			return "";
		}
		if (url.startsWith("http://") || url.startsWith("https://"))
		{
			return url;
		}
		if (url.startsWith("//"))
		{
			return "https:" + url;
		}
		if (url.startsWith("/"))
		{
			return WIKI_BASE_URL + url;
		}
		return WIKI_BASE_URL + "/" + url;
	}
}

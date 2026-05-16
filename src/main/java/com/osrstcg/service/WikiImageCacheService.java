package com.osrstcg.service;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

@Slf4j
@Singleton
public class WikiImageCacheService
{
	private static final String WIKI_BASE_URL = "https://oldschool.runescape.wiki";

	private final Map<String, CompletableFuture<BufferedImage>> imageFutures = new ConcurrentHashMap<>();

	@Inject
	public WikiImageCacheService()
	{
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

		CompletableFuture<BufferedImage> future = imageFutures.get(normalized);
		if (future == null || !future.isDone())
		{
			// Kick off a load if requested before preload path gets here.
			ensureLoad(normalized);
			return null;
		}

		try
		{
			return future.getNow(null);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private void ensureLoad(String rawUrl)
	{
		String url = normalizeUrl(rawUrl);
		if (url.isEmpty())
		{
			return;
		}

		imageFutures.computeIfAbsent(url, key -> CompletableFuture.supplyAsync(() -> loadImage(key)));
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
				URLConnection connection = new URL(candidate).openConnection();
				connection.setConnectTimeout(3000);
				connection.setReadTimeout(4000);
				connection.setRequestProperty("User-Agent", "Mozilla/5.0 (osrstcg)");
				try (InputStream inputStream = connection.getInputStream())
				{
					BufferedImage image = ImageIO.read(inputStream);
					if (image != null)
					{
						persistToDisk(url, image);
						return image;
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
		candidates.add(normalized);

		// OSRS Wiki thumb URLs can 404; fallback to canonical file resolver.
		String fromThumb = extractFilenameFromThumbPath(rawUrl);
		if (!fromThumb.isEmpty())
		{
			candidates.add(specialFilePathUrl(fromThumb));
			addPotionDoseFallbacks(candidates, fromThumb);
		}

		// Generic fallback: use final path segment.
		String fromPath = extractFilenameFromPath(normalized);
		if (!fromPath.isEmpty())
		{
			String specialPath = specialFilePathUrl(fromPath);
			if (!candidates.contains(specialPath))
			{
				candidates.add(specialPath);
			}
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
			String specialPath = specialFilePathUrl(fourDose);
			if (!candidates.contains(specialPath))
			{
				candidates.add(specialPath);
			}
		}

		if (filename.endsWith("_mix_detail.png") && !filename.contains("(2)"))
		{
			String twoDose = filename.replace("_mix_detail.png", "_mix(2)_detail.png");
			String specialPath = specialFilePathUrl(twoDose);
			if (!candidates.contains(specialPath))
			{
				candidates.add(specialPath);
			}
		}
	}

	private String specialFilePathUrl(String filename)
	{
		String safe = filename == null ? "" : filename.trim();
		if (safe.isEmpty())
		{
			return "";
		}
		// Keep wiki-safe URL encoding for parenthesized dose variants.
		safe = safe.replace("(", "%28").replace(")", "%29");
		return WIKI_BASE_URL + "/w/Special:FilePath/" + safe;
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

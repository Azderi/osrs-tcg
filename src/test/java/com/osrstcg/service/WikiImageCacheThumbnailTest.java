package com.osrstcg.service;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import javax.imageio.ImageIO;
import okhttp3.OkHttpClient;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Characterizes the disk-thumbnail cache: the persisted thumbnail must decode to the
 * exact image the memory cache held when it was produced from the full-resolution
 * source, so repeat album visits render pixel-identical art.
 */
public class WikiImageCacheThumbnailTest
{
	// Unroutable host: a regression that misses both disk caches fails fast instead of
	// silently fetching from the wiki inside a unit test.
	private static final String URL_A = "https://127.0.0.1:1/images/Test_a_detail.png";
	private static final String URL_B = "https://127.0.0.1:1/images/Test_b_detail.png";
	private static final String URL_C = "https://127.0.0.1:1/images/Test_c_detail.png";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void firstLoadWritesThumbnailAndRoundTripsPixelIdentical() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeFullResPng(dir, URL_A, 400, 300);

		WikiImageCacheService first = newService(dir);
		first.preloadAndAwait(List.of(URL_A), 10_000L);
		BufferedImage fromFullRes = first.getIfPresent(URL_A);
		Assert.assertNotNull("image should load from the full-res disk file", fromFullRes);
		Assert.assertTrue("thumbnail file should be written on first load",
			Files.isRegularFile(thumbFile(dir, URL_A)));

		// Remove the full-res file so only the thumbnail can serve the reload.
		Files.delete(dir.resolve(sha256Hex(URL_A) + ".png"));
		WikiImageCacheService second = newService(dir);
		second.preloadAndAwait(List.of(URL_A), 10_000L);
		BufferedImage fromThumb = second.getIfPresent(URL_A);
		Assert.assertNotNull("image should load from the thumbnail file", fromThumb);
		assertPixelIdentical(fromFullRes, fromThumb);
	}

	@Test
	public void sourceAlreadyWithinEdgeCapWritesNoThumbnail() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		// Card.json image URLs are 130px wiki thumbs, so most cached files are already small;
		// duplicating them as thumbnails would double disk usage for no decode win.
		writeFullResPng(dir, URL_A, 120, 90);

		WikiImageCacheService svc = newService(dir);
		svc.preloadAndAwait(List.of(URL_A), 10_000L);
		Assert.assertNotNull(svc.getIfPresent(URL_A));
		Assert.assertFalse("no thumbnail for a source already within the edge cap",
			Files.exists(thumbFile(dir, URL_A)));
	}

	@Test
	public void portraitWikiThumbSizedSourceWritesNoThumbnail() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		// Wiki "130px" thumbs are 130 WIDE; portrait art runs taller (half the catalog).
		// Decoding these is already cheap, so duplicating them as thumbnails buys nothing.
		writeFullResPng(dir, URL_A, 130, 180);

		WikiImageCacheService svc = newService(dir);
		svc.preloadAndAwait(List.of(URL_A), 10_000L);
		Assert.assertNotNull(svc.getIfPresent(URL_A));
		Assert.assertFalse("no thumbnail for a wiki-thumb-sized portrait source",
			Files.exists(thumbFile(dir, URL_A)));
	}

	@Test
	public void corruptThumbnailFallsBackToFullResAndSelfHeals() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeFullResPng(dir, URL_A, 400, 300);
		Path thumb = thumbFile(dir, URL_A);
		Files.createDirectories(thumb.getParent());
		Files.write(thumb, "not a png".getBytes(StandardCharsets.UTF_8));

		WikiImageCacheService svc = newService(dir);
		svc.preloadAndAwait(List.of(URL_A), 10_000L);
		BufferedImage recovered = svc.getIfPresent(URL_A);
		Assert.assertNotNull("corrupt thumbnail must fall back to the full-res file", recovered);
		Assert.assertNotNull("thumbnail should be rewritten after the fallback",
			ImageIO.read(thumb.toFile()));

		Files.delete(thumb);
		WikiImageCacheService fresh = newService(dir);
		fresh.preloadAndAwait(List.of(URL_A), 10_000L);
		assertPixelIdentical(recovered, fresh.getIfPresent(URL_A));
	}

	@Test
	public void memoryCacheEvictsOldestWhenByteBudgetExceeded() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeFullResPng(dir, URL_A, 400, 300);
		writeFullResPng(dir, URL_B, 400, 300);
		writeFullResPng(dir, URL_C, 400, 300);

		// Each cached image is ~130x98 ARGB (~51 KB); budget fits two but not three.
		WikiImageCacheService svc = newService(dir, 120L * 1024);
		svc.preloadAndAwait(List.of(URL_A), 10_000L);
		svc.preloadAndAwait(List.of(URL_B), 10_000L);
		svc.preloadAndAwait(List.of(URL_C), 10_000L);

		Assert.assertNull("oldest entry should be evicted past the byte budget", svc.getIfPresent(URL_A));
		Assert.assertNotNull(svc.getIfPresent(URL_B));
		Assert.assertNotNull(svc.getIfPresent(URL_C));
	}

	@Test
	public void singleImageLargerThanBudgetIsStillCached() throws Exception
	{
		Path dir = tmp.getRoot().toPath();
		writeFullResPng(dir, URL_A, 400, 300);

		WikiImageCacheService svc = newService(dir, 1L);
		svc.preloadAndAwait(List.of(URL_A), 10_000L);
		Assert.assertNotNull("newest image must stay cached even when over budget",
			svc.getIfPresent(URL_A));
	}

	private static WikiImageCacheService newService(Path dir)
	{
		return newService(dir, 32L * 1024 * 1024);
	}

	private static WikiImageCacheService newService(Path dir, long budgetBytes)
	{
		return new WikiImageCacheService(new OkHttpClient(), dir, budgetBytes);
	}

	/** Writes a deterministic full-res PNG at the disk-cache location for the URL. */
	private static void writeFullResPng(Path dir, String url, int width, int height) throws Exception
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setPaint(new GradientPaint(0, 0, Color.ORANGE, width, height, Color.BLUE));
		g.fillRect(0, 0, width, height);
		g.setColor(Color.BLACK);
		g.drawString(url, 10, height / 2);
		g.dispose();
		Files.createDirectories(dir);
		ImageIO.write(image, "png", dir.resolve(sha256Hex(url) + ".png").toFile());
	}

	private static Path thumbFile(Path dir, String url) throws Exception
	{
		return dir.resolve("thumbs-130").resolve(sha256Hex(url) + ".png");
	}

	private static String sha256Hex(String value) throws Exception
	{
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for (byte b : digest)
		{
			sb.append("0123456789abcdef".charAt((b >> 4) & 0xF)).append("0123456789abcdef".charAt(b & 0xF));
		}
		return sb.toString();
	}

	private static void assertPixelIdentical(BufferedImage expected, BufferedImage actual)
	{
		Assert.assertEquals("width", expected.getWidth(), actual.getWidth());
		Assert.assertEquals("height", expected.getHeight(), actual.getHeight());
		for (int y = 0; y < expected.getHeight(); y++)
		{
			for (int x = 0; x < expected.getWidth(); x++)
			{
				if (expected.getRGB(x, y) != actual.getRGB(x, y))
				{
					Assert.fail("pixel differs at " + x + "," + y);
				}
			}
		}
	}
}

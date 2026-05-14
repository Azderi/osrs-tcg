package com.runelitetcg.persist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Gzip-compresses JSON and Base64-encodes it with an {@code RLTCG_v1:} prefix for config storage.
 */
@Slf4j
public final class TcgStateStorageEncoding
{
	static final String GZIP_V1_PREFIX = "RLTCG_v1:";

	private TcgStateStorageEncoding()
	{
	}

	public static String encode(String plainJson)
	{
		try
		{
			byte[] utf8 = Objects.requireNonNullElse(plainJson, "").getBytes(StandardCharsets.UTF_8);
			byte[] compressed = gzipCompress(utf8);
			return GZIP_V1_PREFIX + Base64.getEncoder().encodeToString(compressed);
		}
		catch (IOException ex)
		{
			log.warn("OSRS TCG state compression failed", ex);
			return "";
		}
	}

	public static String decode(String stored)
	{
		String s = Objects.requireNonNullElse(stored, "");
		if (s.isEmpty())
		{
			return "";
		}
		try
		{
			if (s.length() <= GZIP_V1_PREFIX.length() || !s.startsWith(GZIP_V1_PREFIX))
			{
				throw new IllegalArgumentException("expected RLTCG_v1 blob");
			}
			byte[] compressed = Base64.getDecoder().decode(s.substring(GZIP_V1_PREFIX.length()));
			return gzipDecompress(compressed);
		}
		catch (IllegalArgumentException | IOException ex)
		{
			log.warn("OSRS TCG state decode failed", ex);
			return "";
		}
	}

	private static byte[] gzipCompress(byte[] input) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(input.length + 32, 512));
		try (GZIPOutputStream gzos = new GZIPOutputStream(baos))
		{
			gzos.write(input);
		}
		return baos.toByteArray();
	}

	private static String gzipDecompress(byte[] compressed) throws IOException
	{
		try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed)))
		{
			return new String(gzis.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}

package com.osrstcg.persist;

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
 * Gzip-compresses JSON, XOR-obfuscates with a plugin salt, and Base64-encodes with an {@code RLTCG_v2:} prefix.
 */
@Slf4j
public final class TcgStateStorageEncoding
{
	static final String STORAGE_PREFIX = "RLTCG_v2:";

	private static final byte[] XOR_SALT = {
		(byte) 0x52, (byte) 0x4c, (byte) 0x54, (byte) 0x43, (byte) 0x47,
		(byte) 0x7c, (byte) 0x6f, (byte) 0x73, (byte) 0x72, (byte) 0x73,
		(byte) 0x2d, (byte) 0x74, (byte) 0x63, (byte) 0x67, (byte) 0x21,
	};

	private TcgStateStorageEncoding()
	{
	}

	public static String encode(String plainJson)
	{
		try
		{
			byte[] utf8 = Objects.requireNonNullElse(plainJson, "").getBytes(StandardCharsets.UTF_8);
			byte[] compressed = gzipCompress(utf8);
			xorWithSalt(compressed);
			return STORAGE_PREFIX + Base64.getEncoder().encodeToString(compressed);
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
			if (s.length() <= STORAGE_PREFIX.length() || !s.startsWith(STORAGE_PREFIX))
			{
				throw new IllegalArgumentException("expected RLTCG_v2 blob");
			}
			byte[] compressed = Base64.getDecoder().decode(s.substring(STORAGE_PREFIX.length()));
			xorWithSalt(compressed);
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

	private static void xorWithSalt(byte[] data)
	{
		for (int i = 0; i < data.length; i++)
		{
			data[i] ^= XOR_SALT[i % XOR_SALT.length];
		}
	}
}

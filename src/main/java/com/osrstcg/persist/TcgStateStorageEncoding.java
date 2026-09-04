package com.osrstcg.persist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;
/**
 * Local saves: gzip-compress JSON (fast deflate) and Base64-encode with {@code RLTCG_v3:}.
 * Legacy {@code RLTCG_v2:} blobs (gzip + XOR + Base64) are still decoded for old on-disk saves.
 */
@Slf4j
public final class TcgStateStorageEncoding
{
	static final String STORAGE_PREFIX_V3 = "RLTCG_v3:";
	static final String STORAGE_PREFIX_V2 = "RLTCG_v2:";

	private static final byte[] XOR_SALT = {
		(byte) 0x52, (byte) 0x4c, (byte) 0x54, (byte) 0x43, (byte) 0x47,
		(byte) 0x7c, (byte) 0x6f, (byte) 0x73, (byte) 0x72, (byte) 0x73,
		(byte) 0x2d, (byte) 0x74, (byte) 0x63, (byte) 0x67, (byte) 0x21,
	};

	private TcgStateStorageEncoding()
	{
	}
/** Gzip-compresses (fastest level) and Base64-encodes {@code plainJson} with the {@code RLTCG_v3:} prefix. */
	public static String encode(String plainJson)
	{
		try
		{
			byte[] utf8 = Objects.requireNonNullElse(plainJson, "").getBytes(StandardCharsets.UTF_8);
			byte[] compressed = gzipCompress(utf8);
			return STORAGE_PREFIX_V3 + Base64.getEncoder().encodeToString(compressed);
		}
		catch (IOException ex)
		{
			log.warn("OSRS TCG state compression failed", ex);
			return "";
		}
	}
/**
	 * Decodes a {@code RLTCG_v3:} or legacy {@code RLTCG_v2:} blob back to plain JSON (v2 additionally
	 * XOR-decrypted with {@link #XOR_SALT} before gzip decompression). Returns empty on blank input or
	 * any decode failure.
	 */
	public static String decode(String stored)
	{
		String s = Objects.requireNonNullElse(stored, "");
		if (s.isEmpty())
		{
			return "";
		}
		try
		{
			if (s.startsWith(STORAGE_PREFIX_V3))
			{
				byte[] compressed = Base64.getDecoder().decode(s.substring(STORAGE_PREFIX_V3.length()));
				return gzipDecompress(compressed);
			}
			if (s.startsWith(STORAGE_PREFIX_V2))
			{
				byte[] compressed = Base64.getDecoder().decode(s.substring(STORAGE_PREFIX_V2.length()));
				xorWithSalt(compressed);
				return gzipDecompress(compressed);
			}
			throw new IllegalArgumentException("expected RLTCG_v2 or RLTCG_v3 blob");
		}
		catch (IllegalArgumentException | IOException ex)
		{
			log.warn("OSRS TCG state decode failed", ex);
			return "";
		}
	}
/** Gzip-compresses {@code input} using {@link FastGzipOutputStream} (best-speed deflate level). */
	private static byte[] gzipCompress(byte[] input) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(256, input.length / 3 + 64));
		try (GZIPOutputStream gzos = new FastGzipOutputStream(baos))
		{
			gzos.write(input);
		}
		return baos.toByteArray();
	}
/** Gzip-decompresses {@code compressed} into a UTF-8 string. */
	private static String gzipDecompress(byte[] compressed) throws IOException
	{
		try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed)))
		{
			return new String(gzis.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
/** XORs {@code data} in place with the repeating {@link #XOR_SALT} (used only for legacy v2 blobs). */
	private static void xorWithSalt(byte[] data)
	{
		for (int i = 0; i < data.length; i++)
		{
			data[i] ^= XOR_SALT[i % XOR_SALT.length];
		}
	}
/** {@link GZIPOutputStream} forced to {@link Deflater#BEST_SPEED} to keep save writes fast. */
	private static final class FastGzipOutputStream extends GZIPOutputStream
	{
/** Wraps {@code out} and lowers the deflate level to best-speed. */
		private FastGzipOutputStream(ByteArrayOutputStream out) throws IOException
		{
			super(out);
			def.setLevel(Deflater.BEST_SPEED);
		}
	}
}

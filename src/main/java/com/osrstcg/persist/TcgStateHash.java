package com.osrstcg.persist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
/** SHA-256 hashing helper used to verify save-file writes/reads round-trip correctly. */
public final class TcgStateHash
{
	private TcgStateHash()
	{
	}
/** Returns the lowercase hex SHA-256 digest of {@code s} (treated as UTF-8; null treated as empty). */
	public static String hexOfUtf8(String s)
	{
		String input = s == null ? "" : s;
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				sb.append(String.format("%02x", b & 0xff));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}

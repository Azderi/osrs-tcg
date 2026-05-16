package com.osrstcg.persist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class TcgStateHash
{
	private TcgStateHash()
	{
	}

	static String hexOfUtf8(String s)
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

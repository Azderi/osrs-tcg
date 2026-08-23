package com.osrstcg.cloud.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

@Singleton
public final class ProfileKeyHasher
{
	private final ConfigManager configManager;

	@Inject
	ProfileKeyHasher(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** 64-char hex SHA-256 of the current RSProfile key, or null if unavailable. */
	public String currentProfileKeyHash()
	{
		String key = configManager.getRSProfileKey();
		if (key == null || key.isEmpty())
		{
			return null;
		}
		return sha256Hex(key);
	}

	public static String sha256Hex(String value)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash)
			{
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}

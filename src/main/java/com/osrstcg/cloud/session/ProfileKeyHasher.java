package com.osrstcg.cloud.session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
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

	/** 64-char hex SHA-256 of decimal {@code accountHash}, or null when unavailable. */
	public static String accountDirName(long accountHash)
	{
		if (accountHash == -1L)
		{
			return null;
		}
		return sha256Hex(Long.toString(accountHash));
	}

	/** {@code ~/.runelite/OSRS-TCG}. */
	public static Path tcgRoot()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG");
	}

	/** {@code ~/.runelite/OSRS-TCG/profiles}. */
	public static Path profilesRoot()
	{
		return tcgRoot().resolve("profiles");
	}

	/** Per-account profile dir {@code profiles/{sha256(accountHash)}}, or null when unavailable. */
	public static Path profileDir(long accountHash)
	{
		String id = accountDirName(accountHash);
		return id == null ? null : profilesRoot().resolve(id);
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

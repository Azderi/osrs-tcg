package com.osrstcg.cloud.session;

import com.osrstcg.persist.TcgStateHash;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

/** Hashes the local RSProfile key (not account credentials). */
@Singleton
public final class ProfileKeyHasher
{
	private final ConfigManager configManager;

	@Inject
	ProfileKeyHasher(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	public String currentProfileKeyHash()
	{
		String key = configManager.getRSProfileKey();
		if (key == null || key.isEmpty())
		{
			return null;
		}
		return sha256Hex(key);
	}

	public static String accountDirName(long accountHash)
	{
		if (accountHash == -1L)
		{
			return null;
		}
		return sha256Hex(Long.toString(accountHash));
	}

	public static Path tcgRoot()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG");
	}

	public static Path profilesRoot()
	{
		return tcgRoot().resolve("profiles");
	}

	public static String sha256Hex(String value)
	{
		return TcgStateHash.hexOfUtf8(value);
	}
}

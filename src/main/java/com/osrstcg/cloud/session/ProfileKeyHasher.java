package com.osrstcg.cloud.session;

import com.osrstcg.persist.TcgStateHash;
import java.nio.file.Path;
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

	public String currentProfileKeyHash()
	{
		String key = configManager.getRSProfileKey();
		if (key == null || key.isEmpty())
		{
			return null;
		}
		return TcgStateHash.hexOfUtf8(key);
	}

	public static String accountDirName(long accountHash)
	{
		if (accountHash == -1L)
		{
			return null;
		}
		return TcgStateHash.hexOfUtf8(Long.toString(accountHash));
	}

	public static Path tcgRoot()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG");
	}

	public static Path profilesRoot()
	{
		return tcgRoot().resolve("profiles");
	}
}

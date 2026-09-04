package com.osrstcg.cloud.session;

import com.osrstcg.persist.TcgStateHash;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
/**
 * Derives stable, hashed identifiers used for cloud pairing and local cache directory naming:
 * a hash of the current RuneLite profile key, and hashes of account hashes for per-account dirs.
 */
@Singleton
public final class ProfileKeyHasher
{
	private final ConfigManager configManager;

	@Inject
	ProfileKeyHasher(ConfigManager configManager)
	{
		this.configManager = configManager;
	}
/** Hex hash of the active RuneLite profile key, or {@code null} if no profile key is set. */
	public String currentProfileKeyHash()
	{
		String key = configManager.getRSProfileKey();
		if (key == null || key.isEmpty())
		{
			return null;
		}
		return TcgStateHash.hexOfUtf8(key);
	}
/** Hex hash of {@code accountHash} used as the local cache directory name, or {@code null} if unset (-1). */
	public static String accountDirName(long accountHash)
	{
		if (accountHash == -1L)
		{
			return null;
		}
		return TcgStateHash.hexOfUtf8(Long.toString(accountHash));
	}
/** Root directory under the RuneLite home where this plugin stores local data. */
	public static Path tcgRoot()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG");
	}
/** Directory holding per-profile local caches, under {@link #tcgRoot()}. */
	public static Path profilesRoot()
	{
		return tcgRoot().resolve("profiles");
	}
}

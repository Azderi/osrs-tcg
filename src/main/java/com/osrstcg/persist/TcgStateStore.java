package com.osrstcg.persist;

import com.osrstcg.model.TcgState;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

@Singleton
@Slf4j
public class TcgStateStore
{
	private static final String GROUP = "osrstcg";
	private static final String STATE_KEY = "state";
	private static final String STATE_HASH_KEY = "hash";
	private final ConfigManager configManager;
	private final TcgStateCodec stateCodec;

	@Inject
	public TcgStateStore(ConfigManager configManager, TcgStateCodec stateCodec)
	{
		this.configManager = configManager;
		this.stateCodec = stateCodec;
	}

	public TcgState load()
	{
		String rawState = getProfileScoped(STATE_KEY);
		String expectedHex = getProfileScoped(STATE_HASH_KEY);
		if (rawState != null && !rawState.isEmpty())
		{
			if (expectedHex == null || expectedHex.isEmpty())
			{
				log.info("OSRS TCG state has no integrity hash yet; it will be written on next save.");
			}
			else
			{
				String actualHex = TcgStateHash.hexOfUtf8(rawState);
				if (!actualHex.equalsIgnoreCase(expectedHex.trim()))
				{
					log.warn("OSRS TCG state integrity check failed (hash mismatch). Using empty state.");
					return TcgState.empty();
				}
			}
		}
		String json = TcgStateStorageEncoding.decode(rawState);
		return stateCodec.fromJson(json);
	}

	public void save(TcgState state)
	{
		if (state == null)
		{
			return;
		}

		String json = stateCodec.toJson(state);
		String stored = TcgStateStorageEncoding.encode(json);
		String hashHex = TcgStateHash.hexOfUtf8(stored);
		writeProfileScoped(STATE_KEY, stored);
		writeProfileScoped(STATE_HASH_KEY, hashHex);

		String roundTrip = getProfileScoped(STATE_KEY);
		String roundTripHash = getProfileScoped(STATE_HASH_KEY);
		if (!Objects.equals(stored, roundTrip))
		{
			log.error("OSRS TCG state save verification failed: stored payload mismatch after write.");
		}
		else if (roundTripHash == null || !hashHex.equalsIgnoreCase(roundTripHash.trim()))
		{
			log.error("OSRS TCG state save verification failed: hash mismatch after write.");
		}
	}

	private void writeProfileScoped(String key, String value)
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null || profileKey.isEmpty())
		{
			configManager.setConfiguration(GROUP, key, value);
		}
		else
		{
			configManager.setConfiguration(GROUP, profileKey, key, value);
		}
	}

	private String getProfileScoped(String key)
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null || profileKey.isEmpty())
		{
			return configManager.getConfiguration(GROUP, key);
		}
		return configManager.getConfiguration(GROUP, profileKey, key);
	}
}

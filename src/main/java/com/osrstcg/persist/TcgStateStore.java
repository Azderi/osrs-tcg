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
	private static final String STATE_BACKUP_KEY = "stateBackup";
	private static final String STATE_BACKUP_HASH_KEY = "hashBackup";

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
		LoadAttempt primary = tryLoad(STATE_KEY, STATE_HASH_KEY);
		if (primary.outcome == LoadOutcome.SUCCESS)
		{
			if (primary.missingHash)
			{
				log.info("OSRS TCG state has no integrity hash yet; it will be written on next save.");
			}
			return primary.state;
		}

		if (primary.outcome != LoadOutcome.MISSING)
		{
			log.warn("OSRS TCG primary state could not be loaded ({}); trying backup.", primary.outcome);
		}

		LoadAttempt backup = tryLoad(STATE_BACKUP_KEY, STATE_BACKUP_HASH_KEY);
		if (backup.outcome == LoadOutcome.SUCCESS)
		{
			log.warn("OSRS TCG restored state from backup after primary load failed.");
			return backup.state;
		}

		return TcgState.empty();
	}

	public void save(TcgState state)
	{
		if (state == null)
		{
			return;
		}

		String json = stateCodec.toJson(state);
		String stored = TcgStateStorageEncoding.encode(json);
		if (stored.isEmpty())
		{
			log.error("OSRS TCG state save aborted: encoding produced an empty payload.");
			return;
		}

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
		else
		{
			writeProfileScoped(STATE_BACKUP_KEY, roundTrip);
			writeProfileScoped(STATE_BACKUP_HASH_KEY, roundTripHash.trim());
		}
	}

	private LoadAttempt tryLoad(String stateKey, String hashKey)
	{
		String rawState = getProfileScoped(stateKey);
		if (rawState == null || rawState.isEmpty())
		{
			return LoadAttempt.missing();
		}

		String expectedHex = getProfileScoped(hashKey);
		boolean missingHash = expectedHex == null || expectedHex.isEmpty();
		if (!missingHash)
		{
			String actualHex = TcgStateHash.hexOfUtf8(rawState);
			if (!actualHex.equalsIgnoreCase(expectedHex.trim()))
			{
				return LoadAttempt.hashMismatch();
			}
		}

		String json = TcgStateStorageEncoding.decode(rawState);
		if (json.isEmpty())
		{
			return LoadAttempt.decodeFailed();
		}

		return LoadAttempt.success(stateCodec.fromJson(json), missingHash);
	}

	void writeProfileScoped(String key, String value)
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

	String getProfileScoped(String key)
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null || profileKey.isEmpty())
		{
			return configManager.getConfiguration(GROUP, key);
		}
		return configManager.getConfiguration(GROUP, profileKey, key);
	}

	private enum LoadOutcome
	{
		SUCCESS,
		MISSING,
		HASH_MISMATCH,
		DECODE_FAILED
	}

	private static final class LoadAttempt
	{
		private final LoadOutcome outcome;
		private final TcgState state;
		private final boolean missingHash;

		private LoadAttempt(LoadOutcome outcome, TcgState state, boolean missingHash)
		{
			this.outcome = outcome;
			this.state = state;
			this.missingHash = missingHash;
		}

		private static LoadAttempt missing()
		{
			return new LoadAttempt(LoadOutcome.MISSING, TcgState.empty(), false);
		}

		private static LoadAttempt hashMismatch()
		{
			return new LoadAttempt(LoadOutcome.HASH_MISMATCH, TcgState.empty(), false);
		}

		private static LoadAttempt decodeFailed()
		{
			return new LoadAttempt(LoadOutcome.DECODE_FAILED, TcgState.empty(), false);
		}

		private static LoadAttempt success(TcgState state, boolean missingHash)
		{
			return new LoadAttempt(LoadOutcome.SUCCESS, state, missingHash);
		}
	}
}

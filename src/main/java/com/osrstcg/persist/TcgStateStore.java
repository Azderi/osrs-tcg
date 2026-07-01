package com.osrstcg.persist;

import com.osrstcg.model.TcgState;
import java.util.Objects;
import java.util.Optional;
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
	private final TcgStateFileBackupStore fileBackupStore;

	@Inject
	public TcgStateStore(
		ConfigManager configManager,
		TcgStateCodec stateCodec,
		TcgStateFileBackupStore fileBackupStore)
	{
		this.configManager = configManager;
		this.stateCodec = stateCodec;
		this.fileBackupStore = fileBackupStore;
	}

	TcgStateStore(ConfigManager configManager, TcgStateCodec stateCodec)
	{
		this(configManager, stateCodec, null);
	}

	public TcgStateLoadResult load()
	{
		moveOldState();

		LoadAttempt primary = tryLoad(STATE_KEY, STATE_HASH_KEY);
		if (primary.outcome == LoadOutcome.SUCCESS)
		{
			if (primary.missingHash)
			{
				log.info("OSRS TCG state has no integrity hash yet; it will be written on next save.");
			}
			return new TcgStateLoadResult(primary.state, TcgStateLoadSource.PRIMARY, false, false, false);
		}

		boolean primaryFailed = primary.outcome != LoadOutcome.MISSING;
		if (primaryFailed)
		{
			log.warn("OSRS TCG primary state could not be loaded ({}); trying configuration backup.",
				primary.outcome);
		}

		LoadAttempt configBackup = tryLoad(STATE_BACKUP_KEY, STATE_BACKUP_HASH_KEY);
		if (configBackup.outcome == LoadOutcome.SUCCESS)
		{
			log.warn("OSRS TCG restored state from configuration backup after primary load failed.");
			return new TcgStateLoadResult(
				configBackup.state,
				TcgStateLoadSource.CONFIG_BACKUP,
				true,
				false,
				false);
		}

		boolean configBackupFailed = primaryFailed && configBackup.outcome != LoadOutcome.MISSING;
		if (configBackupFailed)
		{
			log.warn("OSRS TCG configuration backup could not be loaded ({}); trying file backup.",
				configBackup.outcome);
		}
		else if (primaryFailed)
		{
			log.warn("OSRS TCG configuration backup is missing; trying file backup.");
		}

		Optional<TcgState> fileBackup = loadMostRecentFileBackup();
		if (fileBackup.isPresent())
		{
			log.warn("OSRS TCG restored state from file backup after configuration backups failed.");
			return new TcgStateLoadResult(
				fileBackup.get(),
				TcgStateLoadSource.FILE_BACKUP,
				true,
				configBackupFailed || primaryFailed,
				false);
		}

		if (primaryFailed)
		{
			log.error(
				"OSRS TCG could not restore state from configuration or file backups (config backup: {}).",
				configBackup.outcome);
		}

		return new TcgStateLoadResult(
			TcgState.empty(),
			TcgStateLoadSource.EMPTY,
			primaryFailed,
			configBackupFailed,
			primaryFailed);
	}

	public Optional<TcgState> loadMostRecentFileBackup()
	{
		if (fileBackupStore == null)
		{
			return Optional.empty();
		}
		return fileBackupStore.loadMostRecentValid();
	}

	/**
	 * Writes the encoded state to the on-disk backup store without updating profile configuration.
	 *
	 * @return true if a validated file backup was written
	 */
	public boolean saveToFileBackup(TcgState state)
	{
		if (state == null || fileBackupStore == null)
		{
			return false;
		}

		String json = stateCodec.toJson(state);
		String stored = TcgStateStorageEncoding.encode(json);
		if (stored.isEmpty())
		{
			log.error("OSRS TCG file backup aborted: encoding produced an empty payload.");
			return false;
		}

		return fileBackupStore.writeBackupIfEnabled(stored);
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
		rotateBackupFromValidPrimary();
		writeProfileScoped(STATE_KEY, stored);
		writeProfileScoped(STATE_HASH_KEY, hashHex);
		if (isBackupMissing())
		{
			writeProfileScoped(STATE_BACKUP_KEY, stored);
			writeProfileScoped(STATE_BACKUP_HASH_KEY, hashHex);
		}

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

	private void rotateBackupFromValidPrimary()
	{
		String currentState = getProfileScoped(STATE_KEY);
		if (currentState == null || currentState.isEmpty())
		{
			return;
		}

		String currentHash = getProfileScoped(STATE_HASH_KEY);
		if (currentHash != null && !currentHash.isEmpty())
		{
			String actualHex = TcgStateHash.hexOfUtf8(currentState);
			if (!actualHex.equalsIgnoreCase(currentHash.trim()))
			{
				return;
			}
			writeProfileScoped(STATE_BACKUP_HASH_KEY, currentHash.trim());
		}

		writeProfileScoped(STATE_BACKUP_KEY, currentState);
	}

	private boolean isBackupMissing()
	{
		String backupState = getProfileScoped(STATE_BACKUP_KEY);
		return backupState == null || backupState.isEmpty();
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

		Optional<TcgState> parsed = stateCodec.tryFromJson(json);
		if (parsed.isEmpty())
		{
			return LoadAttempt.decodeFailed();
		}

		return LoadAttempt.success(parsed.get(), missingHash);
	}

	void writeProfileScoped(String key, String value)
	{
		configManager.setRSProfileConfiguration(GROUP, key, value);
	}

	String getProfileScoped(String key)
	{
		return configManager.getRSProfileConfiguration(GROUP, key);
	}

	void moveOldState()
	{
		String currentState = configManager.getRSProfileConfiguration(GROUP, STATE_KEY);
		if (currentState != null)
		{
			return;
		}

		String currentBackup = configManager.getRSProfileConfiguration(GROUP, STATE_BACKUP_KEY);
		if (currentBackup != null)
		{
			return;
		}

		String oldState = configManager.getConfiguration(GROUP, STATE_KEY);
		String oldBackup = configManager.getConfiguration(GROUP, STATE_BACKUP_KEY);
		if (oldState == null && oldBackup == null)
		{
			return;
		}

		if (oldState != null)
		{
			configManager.setRSProfileConfiguration(GROUP, STATE_KEY, oldState);
			if (!oldState.equals(configManager.getRSProfileConfiguration(GROUP, STATE_KEY)))
			{
				return;
			}
			moveOldHash(STATE_HASH_KEY);
		}

		if (oldBackup != null)
		{
			configManager.setRSProfileConfiguration(GROUP, STATE_BACKUP_KEY, oldBackup);
			if (!oldBackup.equals(configManager.getRSProfileConfiguration(GROUP, STATE_BACKUP_KEY)))
			{
				return;
			}
			moveOldHash(STATE_BACKUP_HASH_KEY);
		}

		configManager.unsetConfiguration(GROUP, STATE_KEY);
		configManager.unsetConfiguration(GROUP, STATE_HASH_KEY);
		configManager.unsetConfiguration(GROUP, STATE_BACKUP_KEY);
		configManager.unsetConfiguration(GROUP, STATE_BACKUP_HASH_KEY);
	}

	private void moveOldHash(String key)
	{
		String value = configManager.getConfiguration(GROUP, key);
		if (value != null)
		{
			configManager.setRSProfileConfiguration(GROUP, key, value);
		}
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

package com.osrstcg.persist;

import com.osrstcg.state.TcgState;
import java.util.List;
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
	private static final String STATE_WRITTEN_AT_KEY = "stateWrittenAt";

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

	/**
	 * Loads from disk only ({@code tcg.save} then newest snapshot). Legacy RSProfile {@code state}/{@code hash}
	 * may seed disk once during migration, then are always unset.
	 */
	public TcgStateLoadResult load()
	{
		migrateObsoleteKeysAndSeedDisk();

		Optional<TcgState> master = loadMaster();
		if (master.isPresent())
		{
			return new TcgStateLoadResult(master.get(), TcgStateLoadSource.DISK);
		}

		Optional<TcgState> snapshot = loadMostRecentSnapshot();
		if (snapshot.isPresent())
		{
			log.warn("OSRS TCG restored state from hash snapshot after tcg.save was missing.");
			return new TcgStateLoadResult(snapshot.get(), TcgStateLoadSource.DISK_SNAPSHOT, false, true);
		}

		return new TcgStateLoadResult(TcgState.empty(), TcgStateLoadSource.EMPTY);
	}

	public Optional<TcgState> loadMaster()
	{
		if (fileBackupStore == null)
		{
			return Optional.empty();
		}
		return fileBackupStore.loadMaster();
	}

	public Optional<TcgState> loadMostRecentSnapshot()
	{
		if (fileBackupStore == null)
		{
			return Optional.empty();
		}
		return fileBackupStore.loadMostRecentSnapshot();
	}

	/** Disk save metadata for the current profile ({@code tcg.save} + snapshots). */
	public List<TcgSaveMetadataEntry> listSaveMetadata()
	{
		if (fileBackupStore == null)
		{
			return List.of();
		}
		return fileBackupStore.listSaveMetadata();
	}

	/** Loads {@code tcg.save} or a hash-named snapshot by exact filename (current profile). */
	public Optional<TcgState> loadByFileName(String fileName)
	{
		if (fileBackupStore == null)
		{
			return Optional.empty();
		}
		return fileBackupStore.loadByFileName(fileName);
	}

	/** True when the current profile backups folder has {@code tcg.save} or hash snapshots on disk. */
	public boolean hasSaveFiles()
	{
		return fileBackupStore != null && fileBackupStore.hasSaveFiles();
	}

	/**
	 * Writes {@code tcg.save}, hash snapshot, and {@code saves.json} (disk only).
	 */
	public boolean saveFullCheckpoint(TcgState state, TcgSaveTrigger trigger)
	{
		Encoded encoded = encode(state);
		if (encoded == null || fileBackupStore == null)
		{
			return false;
		}

		boolean diskOk = fileBackupStore.writeMaster(encoded.blob, encoded.cardCount, encoded.credits, trigger);
		diskOk = fileBackupStore.writeSnapshot(encoded.blob, encoded.cardCount, encoded.credits, trigger) && diskOk;
		return diskOk;
	}

	/**
	 * Writes hash snapshot without updating {@code tcg.save} (disk only).
	 */
	public boolean saveCheckpoint(TcgState state, TcgSaveTrigger trigger)
	{
		Encoded encoded = encode(state);
		if (encoded == null || fileBackupStore == null)
		{
			return false;
		}
		return fileBackupStore.writeSnapshot(encoded.blob, encoded.cardCount, encoded.credits, trigger);
	}

	private Encoded encode(TcgState state)
	{
		if (state == null)
		{
			return null;
		}
		String json = stateCodec.toJson(state);
		String stored = TcgStateStorageEncoding.encode(json);
		if (stored.isEmpty())
		{
			log.error("OSRS TCG state save aborted: encoding produced an empty payload.");
			return null;
		}
		int cardCount = state.getCollectionState().getOwnedInstances().size();
		long credits = state.getEconomyState().getCredits();
		return new Encoded(stored, cardCount, credits);
	}

	/**
	 * Seeds disk from legacy config/backup when needed, then unsets all collection config keys
	 * ({@code state}/{@code hash} and obsolete backup keys).
	 */
	void migrateObsoleteKeysAndSeedDisk()
	{
		moveOldStateIntoProfile();

		boolean hasMaster = fileBackupStore != null && fileBackupStore.loadMaster().isPresent();
		if (!hasMaster && fileBackupStore != null)
		{
			LoadAttempt primary = tryLoadConfig(STATE_KEY, STATE_HASH_KEY);
			LoadAttempt backup = primary.outcome == LoadOutcome.SUCCESS
				? primary
				: tryLoadConfig(STATE_BACKUP_KEY, STATE_BACKUP_HASH_KEY);
			if (backup.outcome == LoadOutcome.SUCCESS)
			{
				String json = stateCodec.toJson(backup.state);
				String stored = TcgStateStorageEncoding.encode(json);
				if (!stored.isEmpty())
				{
					int cardCount = backup.state.getCollectionState().getOwnedInstances().size();
					long credits = backup.state.getEconomyState().getCredits();
					fileBackupStore.writeMaster(stored, cardCount, credits, TcgSaveTrigger.MIGRATION);
					fileBackupStore.writeSnapshot(stored, cardCount, credits, TcgSaveTrigger.MIGRATION);
					log.info("OSRS TCG seeded disk saves from profile configuration during migration.");
				}
			}
		}

		unsetCollectionConfigKeys();
		if (fileBackupStore != null)
		{
			fileBackupStore.rewriteSavesIndexFromDisk();
		}
	}

	private void unsetCollectionConfigKeys()
	{
		unsetProfileScoped(STATE_KEY);
		unsetProfileScoped(STATE_HASH_KEY);
		unsetProfileScoped(STATE_BACKUP_KEY);
		unsetProfileScoped(STATE_BACKUP_HASH_KEY);
		unsetProfileScoped(STATE_WRITTEN_AT_KEY);
		unsetGlobalScoped(STATE_KEY);
		unsetGlobalScoped(STATE_HASH_KEY);
		unsetGlobalScoped(STATE_BACKUP_KEY);
		unsetGlobalScoped(STATE_BACKUP_HASH_KEY);
		unsetGlobalScoped(STATE_WRITTEN_AT_KEY);
	}

	private LoadAttempt tryLoadConfig(String stateKey, String hashKey)
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

		return LoadAttempt.success(parsed.get());
	}

	void writeProfileScoped(String key, String value)
	{
		configManager.setRSProfileConfiguration(GROUP, key, value);
	}

	String getProfileScoped(String key)
	{
		return configManager.getRSProfileConfiguration(GROUP, key);
	}

	void unsetProfileScoped(String key)
	{
		configManager.unsetRSProfileConfiguration(GROUP, key);
	}

	String getGlobalScoped(String key)
	{
		return configManager.getConfiguration(GROUP, key);
	}

	void writeGlobalScoped(String key, String value)
	{
		configManager.setConfiguration(GROUP, key, value);
	}

	void unsetGlobalScoped(String key)
	{
		configManager.unsetConfiguration(GROUP, key);
	}

	void moveOldStateIntoProfile()
	{
		String currentState = getProfileScoped(STATE_KEY);
		if (currentState != null)
		{
			return;
		}

		String currentBackup = getProfileScoped(STATE_BACKUP_KEY);
		if (currentBackup != null)
		{
			return;
		}

		String oldState = getGlobalScoped(STATE_KEY);
		String oldBackup = getGlobalScoped(STATE_BACKUP_KEY);
		if (oldState == null && oldBackup == null)
		{
			return;
		}

		if (oldState != null)
		{
			writeProfileScoped(STATE_KEY, oldState);
			if (!oldState.equals(getProfileScoped(STATE_KEY)))
			{
				return;
			}
			moveOldHash(STATE_HASH_KEY);
		}

		if (oldBackup != null)
		{
			writeProfileScoped(STATE_BACKUP_KEY, oldBackup);
			if (!oldBackup.equals(getProfileScoped(STATE_BACKUP_KEY)))
			{
				return;
			}
			moveOldHash(STATE_BACKUP_HASH_KEY);
		}

		unsetGlobalScoped(STATE_KEY);
		unsetGlobalScoped(STATE_HASH_KEY);
		unsetGlobalScoped(STATE_BACKUP_KEY);
		unsetGlobalScoped(STATE_BACKUP_HASH_KEY);
	}

	private void moveOldHash(String key)
	{
		String value = getGlobalScoped(key);
		if (value != null)
		{
			writeProfileScoped(key, value);
		}
	}

	private enum LoadOutcome
	{
		SUCCESS,
		MISSING,
		HASH_MISMATCH,
		DECODE_FAILED
	}

	private static final class Encoded
	{
		private final String blob;
		private final int cardCount;
		private final long credits;

		private Encoded(String blob, int cardCount, long credits)
		{
			this.blob = blob;
			this.cardCount = cardCount;
			this.credits = credits;
		}
	}

	private static final class LoadAttempt
	{
		private final LoadOutcome outcome;
		private final TcgState state;

		private LoadAttempt(LoadOutcome outcome, TcgState state)
		{
			this.outcome = outcome;
			this.state = state;
		}

		private static LoadAttempt missing()
		{
			return new LoadAttempt(LoadOutcome.MISSING, TcgState.empty());
		}

		private static LoadAttempt hashMismatch()
		{
			return new LoadAttempt(LoadOutcome.HASH_MISMATCH, TcgState.empty());
		}

		private static LoadAttempt decodeFailed()
		{
			return new LoadAttempt(LoadOutcome.DECODE_FAILED, TcgState.empty());
		}

		private static LoadAttempt success(TcgState state)
		{
			return new LoadAttempt(LoadOutcome.SUCCESS, state);
		}
	}
}

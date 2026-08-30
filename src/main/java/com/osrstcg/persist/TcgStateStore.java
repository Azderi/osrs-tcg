package com.osrstcg.persist;

import com.osrstcg.state.TcgState;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TcgStateStore
{
	private final TcgStateCodec stateCodec;
	private final TcgStateFileBackupStore fileBackupStore;

	@Inject
	public TcgStateStore(
		TcgStateCodec stateCodec,
		TcgStateFileBackupStore fileBackupStore)
	{
		this.stateCodec = stateCodec;
		this.fileBackupStore = fileBackupStore;
	}

	TcgStateStore(TcgStateCodec stateCodec)
	{
		this(stateCodec, null);
	}

	/** Loads from the current account dir ({@code tcg.save} then newest snapshot). */
	public TcgStateLoadResult load()
	{
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

	/** Legacy profile-key dirs (migrate upload picker only). */
	public List<TcgSaveMetadataEntry> listLegacySaveMetadata()
	{
		if (fileBackupStore == null)
		{
			return List.of();
		}
		return fileBackupStore.listLegacySaveMetadata();
	}

	public Optional<TcgState> loadByFileName(String fileName)
	{
		return loadByFileName(fileName, null);
	}

	public Optional<TcgState> loadByFileName(String fileName, String accountDirId)
	{
		if (fileBackupStore == null)
		{
			return Optional.empty();
		}
		return fileBackupStore.loadByFileName(fileName, accountDirId);
	}

	public boolean hasLegacySaveFiles()
	{
		return fileBackupStore != null && fileBackupStore.hasLegacySaveFiles();
	}

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
}

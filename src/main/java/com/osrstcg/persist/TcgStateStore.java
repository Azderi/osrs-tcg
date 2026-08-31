package com.osrstcg.persist;

import com.osrstcg.state.TcgState;
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

	/** Loads {@code tcg.save} from the current account dir. */
	public TcgStateLoadResult load()
	{
		Optional<TcgState> master = loadMaster();
		if (master.isPresent())
		{
			return new TcgStateLoadResult(master.get(), TcgStateLoadSource.DISK);
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

	public boolean saveFullCheckpoint(TcgState state, TcgSaveTrigger trigger)
	{
		return writeMaster(state, trigger == null ? TcgSaveTrigger.LOGOUT : trigger);
	}

	public boolean saveCheckpoint(TcgState state, TcgSaveTrigger trigger)
	{
		return writeMaster(state, trigger == null ? TcgSaveTrigger.MANUAL : trigger);
	}

	private boolean writeMaster(TcgState state, TcgSaveTrigger trigger)
	{
		if (state == null || fileBackupStore == null)
		{
			return false;
		}
		String json = stateCodec.toJson(state);
		String stored = TcgStateStorageEncoding.encode(json);
		if (stored.isEmpty())
		{
			log.error("OSRS TCG state save aborted: encoding produced an empty payload.");
			return false;
		}
		return fileBackupStore.writeMaster(stored);
	}
}

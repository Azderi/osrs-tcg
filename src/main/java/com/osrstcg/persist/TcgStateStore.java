package com.osrstcg.persist;

import com.osrstcg.state.TcgState;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
/**
 * Facade over {@link TcgStateFileBackupStore} for loading and saving {@link TcgState}: encodes/decodes
 * via {@link TcgStateCodec} and {@link TcgStateStorageEncoding} on top of the raw file I/O.
 */
@Singleton
@Slf4j
public class TcgStateStore
{
	private final TcgStateCodec stateCodec;
	private final TcgStateFileBackupStore fileBackupStore;
/** Stores the codec and backup store used for all loads/saves. */
	@Inject
	public TcgStateStore(
		TcgStateCodec stateCodec,
		TcgStateFileBackupStore fileBackupStore)
	{
		this.stateCodec = stateCodec;
		this.fileBackupStore = fileBackupStore;
	}
/** Test-only constructor with no backup store; loads return empty and saves no-op. */
	TcgStateStore(TcgStateCodec stateCodec)
	{
		this(stateCodec, null);
	}
/** Loads {@code tcg.save} from the current account dir. Empty if none/invalid. */
	public Optional<TcgState> loadMaster()
	{
		if (fileBackupStore == null)
		{
			return Optional.empty();
		}
		return fileBackupStore.loadMaster();
	}
/** Saves {@code state}. {@code trigger} is retained for call-site documentation only. */
	public boolean saveFullCheckpoint(TcgState state, TcgSaveTrigger trigger)
	{
		return writeMaster(state);
	}
/**
	 * Encodes {@code state} to JSON then to the on-disk storage format and hands it to the file backup
	 * store to write. Fails (without touching disk) if {@code state} is null, there is no backup store,
	 * or encoding produces an empty payload.
	 */
	private boolean writeMaster(TcgState state)
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
/** Clears the logout fallback account hash on the file backup store. */
	public void clearLastKnownAccountHash()
	{
		if (fileBackupStore != null)
		{
			fileBackupStore.clearLastKnownAccountHash();
		}
	}
}

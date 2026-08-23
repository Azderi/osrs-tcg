package com.osrstcg.persist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Keeps on-disk hash snapshots at {@link TcgStateFileBackupStore#MAX_SNAPSHOT_FILES}. */
@Slf4j
final class TcgSnapshotPruner
{
	private final TcgStateFileBackupStore store;

	TcgSnapshotPruner(TcgStateFileBackupStore store)
	{
		this.store = store;
	}

	void pruneExcessSnapshots()
	{
		Path dir = store.saveDirectory();
		if (!Files.isDirectory(dir))
		{
			return;
		}

		List<Path> files = store.listSnapshotFiles(dir);
		if (files.size() <= TcgStateFileBackupStore.MAX_SNAPSHOT_FILES)
		{
			return;
		}

		files.sort(Comparator
			.comparingLong((Path p) -> store.savedAtEpochMsForFile(p.getFileName().toString()))
			.thenComparingLong(store::lastModifiedSafe)
			.reversed());

		for (int i = TcgStateFileBackupStore.MAX_SNAPSHOT_FILES; i < files.size(); i++)
		{
			try
			{
				Files.deleteIfExists(files.get(i));
			}
			catch (IOException ex)
			{
				log.debug("OSRS TCG failed to delete excess snapshot {}", files.get(i), ex);
			}
		}
	}
}

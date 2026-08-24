package com.osrstcg.persist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.extern.slf4j.Slf4j;

/** Read/write {@code saves.json} for {@link TcgStateFileBackupStore}. */
@Slf4j
final class TcgSavesIndexIo
{
	private final TcgStateFileBackupStore store;
	private final Gson gson;

	TcgSavesIndexIo(TcgStateFileBackupStore store, Gson gson)
	{
		this.store = store;
		this.gson = gson;
	}

	TcgSavesIndex read()
	{
		return read(null);
	}

	TcgSavesIndex read(String accountDirId)
	{
		String resolved = store.resolveAccountDirName(accountDirId);
		if (resolved == null)
		{
			return new TcgSavesIndex();
		}
		Path path = store.saveDirectory(resolved);
		if (path == null)
		{
			return new TcgSavesIndex();
		}
		path = path.resolve(TcgStateFileBackupStore.SAVES_INDEX_FILENAME);
		if (!Files.isRegularFile(path))
		{
			return new TcgSavesIndex();
		}
		try
		{
			String raw = Files.readString(path, StandardCharsets.UTF_8);
			TcgSavesIndex parsed = gson.fromJson(raw, TcgSavesIndex.class);
			TcgSavesIndex index = parsed == null ? new TcgSavesIndex() : parsed;
			normalizeEntryNames(index);
			return index;
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.debug("OSRS TCG failed to read saves.json", ex);
			return new TcgSavesIndex();
		}
	}

	void write(TcgSavesIndex index)
	{
		write(null, index);
	}

	void write(String accountDirId, TcgSavesIndex index)
	{
		String resolved = store.resolveAccountDirName(accountDirId);
		if (resolved == null)
		{
			return;
		}
		try
		{
			Path dir = store.saveDirectory(resolved);
			if (dir == null)
			{
				return;
			}
			Files.createDirectories(dir);
			Path target = dir.resolve(TcgStateFileBackupStore.SAVES_INDEX_FILENAME);
			Path temp = Files.createTempFile(dir, "tcg-saves-", ".tmp");
			try
			{
				TcgSavesIndex toWrite = index == null ? new TcgSavesIndex() : index;
				normalizeEntryNames(toWrite);
				String json = gson.toJson(toWrite);
				Files.writeString(temp, json, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
				TcgStateFileBackupStore.moveAtomically(temp, target);
			}
			finally
			{
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex)
		{
			log.warn("OSRS TCG failed to write saves.json", ex);
		}
	}

	/** Promotes legacy {@code file} to {@code name} and drops the legacy field for rewrite. */
	static void normalizeEntryNames(TcgSavesIndex index)
	{
		if (index == null || index.getSaves() == null)
		{
			return;
		}
		for (TcgSaveMetadataEntry entry : index.getSaves())
		{
			if (entry == null)
			{
				continue;
			}
			String resolved = entry.getName();
			if (resolved != null && !resolved.isEmpty())
			{
				entry.setName(resolved);
			}
		}
	}
}

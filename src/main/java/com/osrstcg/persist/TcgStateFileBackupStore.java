package com.osrstcg.persist;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.model.TcgState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

@Singleton
@Slf4j
public class TcgStateFileBackupStore
{
	static final int MAX_BACKUP_FILES = 50;
	private static final Pattern HASH_FILENAME = Pattern.compile("^[a-fA-F0-9]{64}$");

	private final ConfigManager configManager;
	private final TcgStateCodec stateCodec;
	private final Provider<OsrsTcgConfig> config;

	@Inject
	public TcgStateFileBackupStore(
		ConfigManager configManager,
		TcgStateCodec stateCodec,
		Provider<OsrsTcgConfig> config)
	{
		this.configManager = configManager;
		this.stateCodec = stateCodec;
		this.config = config;
	}

	public boolean writeBackupIfEnabled(String encodedBlob)
	{
		if (encodedBlob == null || encodedBlob.isEmpty() || !isFileBackupsEnabled())
		{
			return false;
		}

		String hashHex = TcgStateHash.hexOfUtf8(encodedBlob);
		if (!writeValidatedBackup(encodedBlob, hashHex))
		{
			return false;
		}

		pruneExcessBackups();
		return true;
	}

	public Optional<TcgState> loadMostRecentValid()
	{
		if (!isFileBackupsEnabled())
		{
			return Optional.empty();
		}

		Path dir = backupDirectory();
		if (!Files.isDirectory(dir))
		{
			return Optional.empty();
		}

		List<Path> candidates = listBackupFiles(dir);
		candidates.sort(Comparator.comparing(this::lastModifiedSafe).reversed());

		for (Path file : candidates)
		{
			Optional<TcgState> state = tryLoadBackupFile(file);
			if (state.isPresent())
			{
				return state;
			}
		}

		return Optional.empty();
	}

	boolean writeValidatedBackup(String encodedBlob, String hashHex)
	{
		if (encodedBlob == null || encodedBlob.isEmpty() || hashHex == null || hashHex.isEmpty())
		{
			return false;
		}

		try
		{
			Path dir = backupDirectory();
			Files.createDirectories(dir);

			Path target = dir.resolve(hashHex.toLowerCase());
			if (Files.isRegularFile(target) && validateBackupFile(target))
			{
				return true;
			}

			Path temp = Files.createTempFile(dir, "tcg-backup-", ".tmp");
			try
			{
				Files.writeString(temp, encodedBlob, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

				String readBack = Files.readString(temp, StandardCharsets.UTF_8);
				if (!Objects.equals(encodedBlob, readBack))
				{
					log.warn("OSRS TCG file backup verification failed: read-back payload mismatch.");
					return false;
				}

				String readHash = TcgStateHash.hexOfUtf8(readBack);
				if (!readHash.equalsIgnoreCase(hashHex))
				{
					log.warn("OSRS TCG file backup verification failed: hash mismatch after write.");
					return false;
				}

				if (!tryParseEncodedBlob(readBack).isPresent())
				{
					log.warn("OSRS TCG file backup verification failed: state could not be decoded.");
					return false;
				}

				moveAtomically(temp, target);
				if (!validateBackupFile(target))
				{
					log.warn("OSRS TCG file backup verification failed after commit.");
					Files.deleteIfExists(target);
					return false;
				}

				log.debug("OSRS TCG wrote file backup {}", target.getFileName());
				return true;
			}
			finally
			{
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex)
		{
			log.warn("OSRS TCG failed to write file backup", ex);
			return false;
		}
	}

	boolean validateBackupFile(Path file)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return false;
		}

		String filename = file.getFileName().toString();
		if (!HASH_FILENAME.matcher(filename).matches())
		{
			return false;
		}

		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			if (!filename.equalsIgnoreCase(TcgStateHash.hexOfUtf8(encoded)))
			{
				return false;
			}

			return tryParseEncodedBlob(encoded).isPresent();
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG backup file validation failed for {}", file, ex);
			return false;
		}
	}

	Optional<TcgState> tryLoadBackupFile(Path file)
	{
		if (!validateBackupFile(file))
		{
			return Optional.empty();
		}

		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			return tryParseEncodedBlob(encoded);
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG failed to read backup file {}", file, ex);
			return Optional.empty();
		}
	}

	Path backupDirectory()
	{
		String profileKey = configManager == null ? null : configManager.getRSProfileKey();
		String dirName = profileKey == null || profileKey.isEmpty()
			? "default"
			: TcgStateHash.hexOfUtf8(profileKey);
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "backups", dirName);
	}

	private boolean isFileBackupsEnabled()
	{
		OsrsTcgConfig cfg = config.get();
		return cfg == null || cfg.enableFileBackups();
	}

	private Optional<TcgState> tryParseEncodedBlob(String encoded)
	{
		String json = TcgStateStorageEncoding.decode(encoded);
		if (json.isEmpty())
		{
			return Optional.empty();
		}
		return stateCodec.tryFromJson(json);
	}

	private List<Path> listBackupFiles(Path dir)
	{
		List<Path> files = new ArrayList<>();
		try (var stream = Files.list(dir))
		{
			stream.filter(Files::isRegularFile)
				.filter(path -> HASH_FILENAME.matcher(path.getFileName().toString()).matches())
				.forEach(files::add);
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG failed to list backup directory {}", dir, ex);
		}
		return files;
	}

	private void pruneExcessBackups()
	{
		Path dir = backupDirectory();
		if (!Files.isDirectory(dir))
		{
			return;
		}

		List<Path> files = listBackupFiles(dir);
		if (files.size() <= MAX_BACKUP_FILES)
		{
			return;
		}

		files.sort(Comparator.comparing(this::lastModifiedSafe).reversed());
		for (int i = MAX_BACKUP_FILES; i < files.size(); i++)
		{
			try
			{
				Files.deleteIfExists(files.get(i));
			}
			catch (IOException ex)
			{
				log.debug("OSRS TCG failed to delete excess backup {}", files.get(i), ex);
			}
		}
	}

	private long lastModifiedSafe(Path file)
	{
		try
		{
			return Files.getLastModifiedTime(file).toMillis();
		}
		catch (IOException ex)
		{
			return 0L;
		}
	}

	private static void moveAtomically(Path source, Path target) throws IOException
	{
		try
		{
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}

package com.osrstcg.persist;

import com.osrstcg.cloud.session.ProfileKeyHasher;
import com.osrstcg.state.TcgState;
import com.osrstcg.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
/**
 * Reads and writes the per-account {@code tcg.save} file on disk: resolves the account's save
 * directory, and performs atomic, hash-verified writes and best-effort reads. All I/O here is
 * blocking and must be called off the client thread.
 */
@Singleton
@Slf4j
public class TcgStateFileBackupStore
{
	public static final String MASTER_FILENAME = "tcg.save";
	static final String LEGACY_DEFAULT_DIR = "default";
	private static final Pattern ACCOUNT_DIR_NAME = Pattern.compile("^[a-fA-F0-9]{64}$");

	private final Client client;
	private final TcgStateCodec stateCodec;
	private volatile long lastKnownAccountHash = -1L;
/** Stores the RuneLite client (for account hash lookups) and the state codec used to parse loaded saves. */
	@Inject
	public TcgStateFileBackupStore(
		Client client,
		TcgStateCodec stateCodec)
	{
		this.client = client;
		this.stateCodec = stateCodec;
	}
/** Atomically writes {@code encodedBlob} to {@value #MASTER_FILENAME} in the current account's save directory. */
	public boolean writeMaster(String encodedBlob)
	{
		if (encodedBlob == null || encodedBlob.isEmpty())
		{
			return false;
		}

		String hashHex = TcgStateHash.hexOfUtf8(encodedBlob).toLowerCase(Locale.ROOT);
		return writeMasterFile(encodedBlob, hashHex);
	}
/** Reads and decodes {@value #MASTER_FILENAME} from the current account's save directory, if present and valid. */
	public Optional<TcgState> loadMaster()
	{
		Path dir = saveDirectory();
		if (dir == null)
		{
			return Optional.empty();
		}
		return tryLoadEncodedFile(dir.resolve(MASTER_FILENAME));
	}
/** Returns the client's current account hash, or the last known one if logged out (-1). */
	long resolveAccountHashForIo()
	{
		long hash = client.getAccountHash();
		if (hash != -1L)
		{
			lastKnownAccountHash = hash;
			return hash;
		}
		return lastKnownAccountHash;
	}
/** Hashed directory name for the current account, as used under {@link #profilesRoot()}. */
	public String currentAccountDirName()
	{
		return ProfileKeyHasher.accountDirName(resolveAccountHashForIo());
	}
/** Root directory containing one subdirectory per account. */
	Path profilesRoot()
	{
		return ProfileKeyHasher.profilesRoot();
	}
/** Root directory for legacy per-account backup subdirectories, kept for old save layouts. */
	Path legacyBackupsRoot()
	{
		return ProfileKeyHasher.tcgRoot().resolve("backups");
	}
/** Save directory for the current account. */
	Path saveDirectory()
	{
		return saveDirectory(null);
	}
/**
	 * Save directory for {@code accountDirId}, or the current account's when null/blank.
	 * Returns null when {@code accountDirId} doesn't resolve to a valid directory name.
	 */
	Path saveDirectory(String accountDirId)
	{
		String dirName = resolveAccountDirName(accountDirId);
		if (dirName == null)
		{
			return null;
		}
		Path root = accountDirId == null || accountDirId.isBlank()
			? profilesRoot()
			: legacyBackupsRoot();
		return root.resolve(dirName);
	}
/**
	 * Resolves {@code accountDirId} to a validated directory name: current account when null/blank,
	 * the legacy "default" directory verbatim, or a lowercased 64-hex-char account hash; else null.
	 */
	String resolveAccountDirName(String accountDirId)
	{
		if (accountDirId == null || accountDirId.isBlank())
		{
			return currentAccountDirName();
		}
		String trimmed = accountDirId.trim();
		if (LEGACY_DEFAULT_DIR.equalsIgnoreCase(trimmed))
		{
			return LEGACY_DEFAULT_DIR;
		}
		if (!ACCOUNT_DIR_NAME.matcher(trimmed).matches())
		{
			return null;
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}
/**
	 * Writes {@code encodedBlob} to a temp file in the save directory, verifies it hashes to
	 * {@code expectedHash} by reading it back, then atomically renames it onto {@value #MASTER_FILENAME}.
	 * The temp file is always removed afterwards.
	 */
	private boolean writeMasterFile(String encodedBlob, String expectedHash)
	{
		try
		{
			Path dir = saveDirectory();
			if (dir == null)
			{
				return false;
			}
			Files.createDirectories(dir);

			Path target = dir.resolve(MASTER_FILENAME);
			Path temp = Files.createTempFile(dir, "tcg-save-", ".tmp");
			try
			{
				Files.writeString(temp, encodedBlob, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

				String readBack = Files.readString(temp, StandardCharsets.UTF_8);
				String readHash = TcgStateHash.hexOfUtf8(readBack);
				if (!readHash.equalsIgnoreCase(expectedHash))
				{
					log.warn("OSRS TCG save verification failed: hash mismatch after write.");
					return false;
				}

				AtomicFiles.moveReplace(temp, target);
				log.debug("OSRS TCG wrote save file {}", target.getFileName());
				return true;
			}
			finally
			{
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex)
		{
			log.warn("OSRS TCG failed to write save file {}", MASTER_FILENAME, ex);
			return false;
		}
	}
/** Reads {@code file}, hash-validates and decodes its contents, and parses it into a {@link TcgState}. */
	private Optional<TcgState> tryLoadEncodedFile(Path file)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return Optional.empty();
		}

		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			String hash = TcgStateHash.hexOfUtf8(encoded);
			if (!validateMasterContent(encoded, hash))
			{
				return Optional.empty();
			}
			return tryParseEncodedBlob(encoded);
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG failed to read save file {}", file, ex);
			return Optional.empty();
		}
	}
/** Checks {@code encoded}'s hash against {@code expectedHash} (when given) and that it parses into a state. */
	private boolean validateMasterContent(String encoded, String expectedHash)
	{
		if (expectedHash != null && !expectedHash.equalsIgnoreCase(TcgStateHash.hexOfUtf8(encoded)))
		{
			return false;
		}
		return tryParseEncodedBlob(encoded).isPresent();
	}
/** Decodes the storage blob to JSON via {@link TcgStateStorageEncoding} and parses it with {@link #stateCodec}. */
	private Optional<TcgState> tryParseEncodedBlob(String encoded)
	{
		String json = TcgStateStorageEncoding.decode(encoded);
		if (json.isEmpty())
		{
			return Optional.empty();
		}
		try
		{
			return Optional.ofNullable(stateCodec.fromJson(json));
		}
		catch (RuntimeException ex)
		{
			log.debug("OSRS TCG failed to parse save JSON", ex);
			return Optional.empty();
		}
	}
}

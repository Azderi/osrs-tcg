package com.osrstcg.persist;

import com.google.gson.Gson;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import com.osrstcg.state.TcgState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

@Singleton
@Slf4j
public class TcgStateFileBackupStore
{
	public static final int MAX_SNAPSHOT_FILES = 50;
	public static final String MASTER_FILENAME = "tcg.save";
	public static final String SAVES_INDEX_FILENAME = "saves.json";
	/** Legacy RS-profile backup folder name (migrate upload scan only). */
	static final String LEGACY_DEFAULT_DIR = "default";
	private static final Pattern HASH_FILENAME = Pattern.compile("^[a-fA-F0-9]{64}$");
	private static final Pattern ACCOUNT_DIR_NAME = Pattern.compile("^[a-fA-F0-9]{64}$");

	private final Client client;
	private final TcgStateCodec stateCodec;
	private final TcgSavesIndexIo indexIo;
	private final TcgSnapshotPruner snapshotPruner;
	private volatile long lastKnownAccountHash = -1L;

	@Inject
	public TcgStateFileBackupStore(
		Client client,
		TcgStateCodec stateCodec,
		Gson gson)
	{
		this.client = client;
		this.stateCodec = stateCodec;
		this.indexIo = new TcgSavesIndexIo(this, gson);
		this.snapshotPruner = new TcgSnapshotPruner(this);
	}

	/**
	 * Overwrites {@code tcg.save} and upserts its row in {@code saves.json}.
	 */
	public boolean writeMaster(String encodedBlob, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		if (encodedBlob == null || encodedBlob.isEmpty())
		{
			return false;
		}

		String hashHex = TcgStateHash.hexOfUtf8(encodedBlob).toLowerCase(Locale.ROOT);
		if (!writeValidatedNamedFile(MASTER_FILENAME, encodedBlob, hashHex, false))
		{
			return false;
		}

		upsertMasterMetadata(
			hashHex,
			cardCount,
			credits,
			trigger == null ? TcgSaveTrigger.COLLECTION_CHANGE : trigger);
		return true;
	}

	/**
	 * Writes a content-addressed hash snapshot, updates {@code saves.json}, and prunes to 50 snapshots.
	 */
	public boolean writeSnapshot(String encodedBlob, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		if (encodedBlob == null || encodedBlob.isEmpty())
		{
			return false;
		}

		String hashHex = TcgStateHash.hexOfUtf8(encodedBlob).toLowerCase(Locale.ROOT);
		boolean wrote = writeValidatedNamedFile(hashHex, encodedBlob, hashHex, true);
		if (!wrote)
		{
			Path dir = saveDirectory();
			if (dir == null)
			{
				return false;
			}
			Path existing = dir.resolve(hashHex);
			if (!Files.isRegularFile(existing) || !validateSnapshotFile(existing))
			{
				return false;
			}
		}

		upsertSnapshotMetadata(
			hashHex,
			cardCount,
			credits,
			trigger == null ? TcgSaveTrigger.MANUAL : trigger);
		pruneExcessSnapshots();
		rewriteSavesIndexFromDisk();
		return true;
	}

	public Optional<TcgState> loadMaster()
	{
		Path dir = saveDirectory();
		if (dir == null)
		{
			return Optional.empty();
		}
		return tryLoadEncodedFile(dir.resolve(MASTER_FILENAME), false);
	}

	public Optional<TcgState> loadMostRecentSnapshot()
	{
		Path dir = saveDirectory();
		if (dir == null || !Files.isDirectory(dir))
		{
			return Optional.empty();
		}

		List<Path> candidates = listSnapshotFiles(dir);
		candidates.sort(Comparator
			.comparingLong((Path p) -> savedAtEpochMsForFile(p.getFileName().toString()))
			.thenComparingLong(this::lastModifiedSafe)
			.reversed());

		for (Path file : candidates)
		{
			Optional<TcgState> state = tryLoadEncodedFile(file, true);
			if (state.isPresent())
			{
				return state;
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns metadata for {@code tcg.save} and retained snapshots (syncs {@code saves.json} first),
	 * ordered newest {@code savedAt} first (current profile only).
	 */
	public List<TcgSaveMetadataEntry> listSaveMetadata()
	{
		rewriteSavesIndexFromDisk();
		TcgSavesIndex index = readSavesIndex();
		List<TcgSaveMetadataEntry> saves = index.getSaves();
		if (saves == null || saves.isEmpty())
		{
			return List.of();
		}
		List<TcgSaveMetadataEntry> copy = new ArrayList<>(saves.size());
		for (TcgSaveMetadataEntry entry : saves)
		{
			if (entry == null || entry.getName() == null || entry.getName().isEmpty())
			{
				continue;
			}
			copy.add(new TcgSaveMetadataEntry(
				entry.getName(),
				entry.getCardCount(),
				entry.getCredits(),
				entry.getHash(),
				entry.getSavedAt(),
				entry.getTrigger()));
		}
		copy.sort(Comparator.comparingLong((TcgSaveMetadataEntry e) -> parseSavedAtEpochMs(e.getSavedAt())).reversed());
		return copy;
	}

	/**
	 * Loads {@code tcg.save} or a hash-named snapshot by exact filename from the current profile.
	 */
	public Optional<TcgState> loadByFileName(String fileName)
	{
		return loadByFileName(fileName, null);
	}

	/** Loads from the current account dir, or {@code accountDirId} when set (legacy migrate dirs). */
	public Optional<TcgState> loadByFileName(String fileName, String accountDirId)
	{
		if (fileName == null || fileName.isEmpty())
		{
			return Optional.empty();
		}
		String name = fileName.trim();
		Path dir = saveDirectory(accountDirId);
		if (dir == null)
		{
			return Optional.empty();
		}
		if (MASTER_FILENAME.equalsIgnoreCase(name))
		{
			return tryLoadEncodedFile(dir.resolve(MASTER_FILENAME), false);
		}
		if (!HASH_FILENAME.matcher(name).matches())
		{
			return Optional.empty();
		}
		return tryLoadEncodedFile(dir.resolve(name.toLowerCase(Locale.ROOT)), true);
	}

	/**
	 * True when the current profile folder contains {@code tcg.save} or any hash snapshot file.
	 * Uses the filesystem directly so a stale/empty {@code saves.json} cannot hide real saves.
	 */
	public boolean hasSaveFiles()
	{
		Path dir = saveDirectory();
		return dir != null && hasSaveFilesInDir(dir);
	}

	/** True when any folder under {@code backups/} contains save files (migrate upload scan). */
	public boolean hasLegacySaveFiles()
	{
		Path root = legacyBackupsRoot();
		if (!Files.isDirectory(root))
		{
			return false;
		}
		try (var stream = Files.list(root))
		{
			return stream.filter(Files::isDirectory)
				.anyMatch(path -> isMigratableBackupDirName(path.getFileName().toString())
					&& hasSaveFilesInDir(path));
		}
		catch (IOException ex)
		{
			return false;
		}
	}

	/**
	 * Save metadata from all legacy dirs under {@code backups/} (migrate upload picker only).
	 * Each entry has {@link TcgSaveMetadataEntry#getSourceDir()} set to the folder id.
	 */
	public List<TcgSaveMetadataEntry> listLegacySaveMetadata()
	{
		Path root = legacyBackupsRoot();
		if (!Files.isDirectory(root))
		{
			return List.of();
		}
		List<TcgSaveMetadataEntry> out = new ArrayList<>();
		try (var stream = Files.list(root))
		{
			stream.filter(Files::isDirectory).forEach(path ->
			{
				String dirName = path.getFileName().toString();
				if (!isMigratableBackupDirName(dirName))
				{
					return;
				}
				rewriteSavesIndexFromDisk(dirName);
				TcgSavesIndex index = readSavesIndex(dirName);
				if (index.getSaves() == null)
				{
					return;
				}
				for (TcgSaveMetadataEntry entry : index.getSaves())
				{
					if (entry == null || entry.getName() == null || entry.getName().isEmpty())
					{
						continue;
					}
					entry.setSourceDir(dirName);
					out.add(new TcgSaveMetadataEntry(
						entry.getName(),
						entry.getCardCount(),
						entry.getCredits(),
						entry.getHash(),
						entry.getSavedAt(),
						entry.getTrigger(),
						dirName));
				}
			});
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG failed to list legacy save directories", ex);
		}
		out.sort(Comparator.comparingLong((TcgSaveMetadataEntry e) -> parseSavedAtEpochMs(e.getSavedAt())).reversed());
		return out;
	}

	long resolveAccountHashForIo()
	{
		if (client != null)
		{
			long hash = client.getAccountHash();
			if (hash != -1L)
			{
				lastKnownAccountHash = hash;
				return hash;
			}
		}
		return lastKnownAccountHash;
	}

	/** Current account profile folder id (64-char hex), or null when no account hash is known. */
	public String currentAccountDirName()
	{
		return ProfileKeyHasher.accountDirName(resolveAccountHashForIo());
	}

	Path profilesRoot()
	{
		return ProfileKeyHasher.profilesRoot();
	}

	Path legacyBackupsRoot()
	{
		return ProfileKeyHasher.tcgRoot().resolve("backups");
	}

	Path saveDirectory()
	{
		return saveDirectory(null);
	}

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
		if (!isSafeAccountDirName(trimmed))
		{
			return null;
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	private boolean isSafeAccountDirName(String name)
	{
		return name != null && ACCOUNT_DIR_NAME.matcher(name).matches();
	}

	private static boolean isMigratableBackupDirName(String dirName)
	{
		if (dirName == null || dirName.isEmpty())
		{
			return false;
		}
		if (LEGACY_DEFAULT_DIR.equalsIgnoreCase(dirName))
		{
			return true;
		}
		return ACCOUNT_DIR_NAME.matcher(dirName).matches();
	}

	private static boolean hasSaveFilesInDir(Path dir)
	{
		if (dir == null || !Files.isDirectory(dir))
		{
			return false;
		}
		if (Files.isRegularFile(dir.resolve(MASTER_FILENAME)))
		{
			return true;
		}
		try (var stream = Files.list(dir))
		{
			return stream.anyMatch(path -> Files.isRegularFile(path)
				&& HASH_FILENAME.matcher(path.getFileName().toString()).matches());
		}
		catch (IOException ex)
		{
			return false;
		}
	}

	private boolean writeValidatedNamedFile(String filename, String encodedBlob, String expectedHash, boolean requireHashName)
	{
		if (filename == null || filename.isEmpty() || encodedBlob == null || encodedBlob.isEmpty())
		{
			return false;
		}

		try
		{
			Path dir = saveDirectory();
			if (dir == null)
			{
				return false;
			}
			Files.createDirectories(dir);

			Path target = dir.resolve(filename);
			if (requireHashName && Files.isRegularFile(target) && hashMatchesFile(target, expectedHash))
			{
				return true;
			}

			Path temp = Files.createTempFile(dir, "tcg-save-", ".tmp");
			try
			{
				Files.writeString(temp, encodedBlob, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

				// Hash-only verify: full gzip+JSON parse on every write was a multi-second hitch for
				// large collections. Load paths still decode/parse; atomic rename covers crash safety.
				String readBack = Files.readString(temp, StandardCharsets.UTF_8);
				String readHash = TcgStateHash.hexOfUtf8(readBack);
				if (!readHash.equalsIgnoreCase(expectedHash))
				{
					log.warn("OSRS TCG save verification failed: hash mismatch after write.");
					return false;
				}

				moveAtomically(temp, target);

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
			log.warn("OSRS TCG failed to write save file {}", filename, ex);
			return false;
		}
	}

	/** Cheap content check without decompressing / parsing the collection JSON. */
	private boolean hashMatchesFile(Path file, String expectedHash)
	{
		if (file == null || !Files.isRegularFile(file) || expectedHash == null || expectedHash.isEmpty())
		{
			return false;
		}
		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			return expectedHash.equalsIgnoreCase(TcgStateHash.hexOfUtf8(encoded));
		}
		catch (IOException ex)
		{
			return false;
		}
	}

	private boolean validateMasterFile(Path file, String expectedHash)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return false;
		}
		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			if (expectedHash != null && !expectedHash.equalsIgnoreCase(TcgStateHash.hexOfUtf8(encoded)))
			{
				return false;
			}
			return tryParseEncodedBlob(encoded).isPresent();
		}
		catch (IOException ex)
		{
			return false;
		}
	}

	boolean validateSnapshotFile(Path file)
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
			log.debug("OSRS TCG snapshot validation failed for {}", file, ex);
			return false;
		}
	}

	private Optional<TcgState> tryLoadEncodedFile(Path file, boolean requireHashName)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return Optional.empty();
		}

		try
		{
			String encoded = Files.readString(file, StandardCharsets.UTF_8);
			if (requireHashName)
			{
				if (!validateSnapshotFile(file))
				{
					return Optional.empty();
				}
			}
			else
			{
				String hash = TcgStateHash.hexOfUtf8(encoded);
				if (!validateMasterFile(file, hash))
				{
					return Optional.empty();
				}
			}
			return tryParseEncodedBlob(encoded);
		}
		catch (IOException ex)
		{
			log.debug("OSRS TCG failed to read save file {}", file, ex);
			return Optional.empty();
		}
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

	private void upsertMasterMetadata(String hashHex, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		TcgSavesIndex index = readSavesIndex();
		List<TcgSaveMetadataEntry> entries = new ArrayList<>(index.getSaves());
		entries.removeIf(e -> e != null && MASTER_FILENAME.equalsIgnoreCase(nullToEmpty(e.getName())));
		entries.add(0, new TcgSaveMetadataEntry(
			MASTER_FILENAME,
			Math.max(0, cardCount),
			credits,
			hashHex,
			Instant.now().toString(),
			trigger.name()));
		index.setSaves(trimToMasterAndSnapshots(entries));
		writeSavesIndex(index);
	}

	private void upsertSnapshotMetadata(String hashHex, int cardCount, long credits, TcgSaveTrigger trigger)
	{
		TcgSavesIndex index = readSavesIndex();
		List<TcgSaveMetadataEntry> entries = new ArrayList<>(index.getSaves());
		entries.removeIf(e -> e != null && hashHex.equalsIgnoreCase(nullToEmpty(e.getName())));
		entries.add(new TcgSaveMetadataEntry(
			hashHex,
			Math.max(0, cardCount),
			credits,
			hashHex,
			Instant.now().toString(),
			trigger.name()));
		index.setSaves(trimToMasterAndSnapshots(entries));
		writeSavesIndex(index);
	}

	void pruneExcessSnapshots()
	{
		snapshotPruner.pruneExcessSnapshots();
	}

	/**
	 * Ensures {@code saves.json} only lists {@code tcg.save} (if present) and up to 50 existing snapshots.
	 */
	void rewriteSavesIndexFromDisk()
	{
		rewriteSavesIndexFromDisk(null);
	}

	void rewriteSavesIndexFromDisk(String accountDirId)
	{
		String resolved = resolveAccountDirName(accountDirId);
		if (resolved == null)
		{
			return;
		}
		Path dir = saveDirectory(resolved);
		if (dir == null)
		{
			return;
		}
		TcgSavesIndex existing = readSavesIndex(resolved);
		List<TcgSaveMetadataEntry> previous = existing.getSaves() == null ? List.of() : existing.getSaves();

		List<TcgSaveMetadataEntry> next = new ArrayList<>();
		Path master = dir.resolve(MASTER_FILENAME);
		if (Files.isRegularFile(master))
		{
			Optional<TcgSaveMetadataEntry> priorMaster = previous.stream()
				.filter(e -> e != null && MASTER_FILENAME.equalsIgnoreCase(nullToEmpty(e.getName())))
				.findFirst();
			try
			{
				String encoded = Files.readString(master, StandardCharsets.UTF_8);
				String hashHex = TcgStateHash.hexOfUtf8(encoded).toLowerCase(Locale.ROOT);
				// Prefer prior metadata counts; only decode when the master hash changed or counts unknown.
				int cardCount = priorMaster.map(TcgSaveMetadataEntry::getCardCount).orElse(-1);
				long credits = priorMaster.map(TcgSaveMetadataEntry::getCredits).orElse(0L);
				String priorHash = priorMaster.map(TcgSaveMetadataEntry::getHash).orElse("");
				if (cardCount < 0 || !hashHex.equalsIgnoreCase(nullToEmpty(priorHash)))
				{
					Optional<TcgState> parsed = tryParseEncodedBlob(encoded);
					if (parsed.isPresent())
					{
						cardCount = parsed.get().getCollectionState().getOwnedInstances().size();
						credits = parsed.get().getEconomyState().getCredits();
					}
					else
					{
						cardCount = Math.max(0, cardCount);
					}
				}
				String savedAt = priorMaster.map(TcgSaveMetadataEntry::getSavedAt).orElse(null);
				if (savedAt == null || savedAt.isEmpty())
				{
					savedAt = Instant.ofEpochMilli(lastModifiedSafe(master)).toString();
				}
				String trigger = priorMaster.map(TcgSaveMetadataEntry::getTrigger).orElse(TcgSaveTrigger.UNKNOWN.name());
				next.add(new TcgSaveMetadataEntry(MASTER_FILENAME, cardCount, credits, hashHex, savedAt, trigger));
			}
			catch (IOException ex)
			{
				log.debug("OSRS TCG failed to index master save", ex);
			}
		}

		List<Path> snapshots = listSnapshotFiles(dir);
		snapshots.sort(Comparator
			.comparingLong((Path p) -> savedAtEpochMsForFile(resolved, p.getFileName().toString()))
			.thenComparingLong(this::lastModifiedSafe)
			.reversed());

		Set<String> seen = new HashSet<>();
		int snapshotCount = 0;
		for (Path file : snapshots)
		{
			if (snapshotCount >= MAX_SNAPSHOT_FILES)
			{
				break;
			}
			String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
			if (!seen.add(name) || !hashMatchesFile(file, name))
			{
				continue;
			}
			Optional<TcgSaveMetadataEntry> prior = previous.stream()
				.filter(e -> e != null && name.equalsIgnoreCase(nullToEmpty(e.getName())))
				.findFirst();
			int cardCount = prior.map(TcgSaveMetadataEntry::getCardCount).orElse(-1);
			long credits = prior.map(TcgSaveMetadataEntry::getCredits).orElse(0L);
			// Skip decompress/parse when metadata already has counts for this hash-named snapshot.
			if (cardCount < 0)
			{
				try
				{
					Optional<TcgState> loaded = tryParseEncodedBlob(Files.readString(file, StandardCharsets.UTF_8));
					if (loaded.isEmpty())
					{
						continue;
					}
					cardCount = loaded.get().getCollectionState().getOwnedInstances().size();
					credits = loaded.get().getEconomyState().getCredits();
				}
				catch (IOException ex)
				{
					log.debug("OSRS TCG failed to index snapshot {}", name, ex);
					continue;
				}
			}
			String savedAt = prior.map(TcgSaveMetadataEntry::getSavedAt).orElse(null);
			if (savedAt == null || savedAt.isEmpty())
			{
				savedAt = Instant.ofEpochMilli(lastModifiedSafe(file)).toString();
			}
			String trigger = prior.map(TcgSaveMetadataEntry::getTrigger).orElse(TcgSaveTrigger.UNKNOWN.name());
			next.add(new TcgSaveMetadataEntry(name, cardCount, credits, name, savedAt, trigger));
			snapshotCount++;
		}

		TcgSavesIndex index = new TcgSavesIndex();
		index.setSaves(next);
		writeSavesIndex(resolved, index);
	}

	private List<TcgSaveMetadataEntry> trimToMasterAndSnapshots(List<TcgSaveMetadataEntry> entries)
	{
		TcgSaveMetadataEntry master = null;
		List<TcgSaveMetadataEntry> snapshots = new ArrayList<>();
		for (TcgSaveMetadataEntry entry : entries)
		{
			if (entry == null || entry.getName() == null)
			{
				continue;
			}
			if (MASTER_FILENAME.equalsIgnoreCase(entry.getName()))
			{
				master = entry;
			}
			else if (HASH_FILENAME.matcher(entry.getName()).matches())
			{
				snapshots.add(entry);
			}
		}
		snapshots.sort(Comparator.comparingLong((TcgSaveMetadataEntry e) -> parseSavedAtEpochMs(e.getSavedAt())).reversed());
		if (snapshots.size() > MAX_SNAPSHOT_FILES)
		{
			snapshots = new ArrayList<>(snapshots.subList(0, MAX_SNAPSHOT_FILES));
		}
		List<TcgSaveMetadataEntry> out = new ArrayList<>();
		if (master != null)
		{
			out.add(master);
		}
		out.addAll(snapshots);
		return out;
	}

	TcgSavesIndex readSavesIndex()
	{
		return indexIo.read();
	}

	TcgSavesIndex readSavesIndex(String accountDirId)
	{
		return indexIo.read(accountDirId);
	}

	void writeSavesIndex(TcgSavesIndex index)
	{
		indexIo.write(index);
	}

	void writeSavesIndex(String accountDirId, TcgSavesIndex index)
	{
		indexIo.write(accountDirId, index);
	}

	long savedAtEpochMsForFile(String filename)
	{
		return savedAtEpochMsForFile(null, filename);
	}

	private long savedAtEpochMsForFile(String accountDirId, String filename)
	{
		TcgSavesIndex index = readSavesIndex(accountDirId);
		if (index.getSaves() != null)
		{
			for (TcgSaveMetadataEntry entry : index.getSaves())
			{
				if (entry != null && filename.equalsIgnoreCase(nullToEmpty(entry.getName())))
				{
					return parseSavedAtEpochMs(entry.getSavedAt());
				}
			}
		}
		String resolved = resolveAccountDirName(accountDirId);
		if (resolved == null)
		{
			return 0L;
		}
		Path dir = saveDirectory(resolved);
		if (dir == null)
		{
			return 0L;
		}
		return lastModifiedSafe(dir.resolve(filename));
	}

	private static long parseSavedAtEpochMs(String savedAt)
	{
		if (savedAt == null || savedAt.isEmpty())
		{
			return 0L;
		}
		try
		{
			return Instant.parse(savedAt).toEpochMilli();
		}
		catch (Exception ex)
		{
			return 0L;
		}
	}

	List<Path> listSnapshotFiles(Path dir)
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
			log.debug("OSRS TCG failed to list save directory {}", dir, ex);
		}
		return files;
	}

	long lastModifiedSafe(Path file)
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

	private static String nullToEmpty(String value)
	{
		return value == null ? "" : value;
	}

	static void moveAtomically(Path source, Path target) throws IOException
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

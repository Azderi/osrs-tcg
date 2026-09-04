package com.osrstcg.cloud.session;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrstcg.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-account cloud session file ({@code cloud-session.json}) under the account-hash profile folder.
 * Blocking I/O — call off the client thread when possible.
 */
@Slf4j
@Singleton
public final class CloudSessionFileStore
{
	static final String SESSION_FILENAME = "cloud-session.json";
	private static final int SCHEMA_VERSION = 1;
	private static final Gson GSON = new Gson();

	private final Path profilesRoot;

	@Inject
	CloudSessionFileStore()
	{
		this(ProfileKeyHasher.profilesRoot());
	}

	CloudSessionFileStore(Path profilesRoot)
	{
		this.profilesRoot = profilesRoot;
	}

	/** Session file path for an account, or {@code null} if the account hash is unset. */
	Path sessionFile(long accountHash)
	{
		String id = ProfileKeyHasher.accountDirName(accountHash);
		return id == null ? null : profilesRoot.resolve(id).resolve(SESSION_FILENAME);
	}

	/** Profile directory for an account, or {@code null} if unset. */
	Path accountDir(long accountHash)
	{
		String id = ProfileKeyHasher.accountDirName(accountHash);
		return id == null ? null : profilesRoot.resolve(id);
	}

	/** Loads the session for {@code accountHash}, or {@code null} if missing/invalid. */
	public SessionData load(long accountHash)
	{
		Path file = sessionFile(accountHash);
		if (file == null || !Files.isRegularFile(file))
		{
			return null;
		}
		try
		{
			String json = Files.readString(file, StandardCharsets.UTF_8);
			if (json == null || json.isBlank())
			{
				return null;
			}
			SessionData data = GSON.fromJson(json, SessionData.class);
			if (data == null || blank(data.accessToken) || blank(data.refreshToken))
			{
				return null;
			}
			return data;
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.warn("Failed reading cloud session {}", file, ex);
			return null;
		}
	}

	/** Atomically writes the session file for {@code accountHash}. No-op if tokens or hash are invalid. */
	public void save(long accountHash, SessionData data)
	{
		if (accountHash == -1L || data == null || blank(data.accessToken) || blank(data.refreshToken))
		{
			return;
		}
		Path file = sessionFile(accountHash);
		if (file == null)
		{
			return;
		}
		SessionData out = new SessionData();
		out.schemaVersion = SCHEMA_VERSION;
		out.accessToken = data.accessToken;
		out.refreshToken = data.refreshToken;
		out.accountId = blank(data.accountId) ? null : data.accountId;
		out.status = blank(data.status) ? null : data.status;
		out.boundAccountHash = accountHash;
		try
		{
			AtomicFiles.writeString(file, GSON.toJson(out), StandardCharsets.UTF_8);
		}
		catch (IOException ex)
		{
			log.warn("Failed writing cloud session for accountHash={}", ProfileKeyHasher.accountDirName(accountHash), ex);
		}
	}

	/** Deletes the session file for an account. Failures are logged and swallowed. */
	public void delete(long accountHash)
	{
		Path file = sessionFile(accountHash);
		if (file == null)
		{
			return;
		}
		try
		{
			Files.deleteIfExists(file);
		}
		catch (IOException ex)
		{
			log.debug("Failed deleting cloud session for accountHash={}", ProfileKeyHasher.accountDirName(accountHash), ex);
		}
	}

	/**
	 * Deletes the entire per-account profile directory (save, session, leftovers).
	 * Failures are logged and swallowed.
	 */
	public void deleteAccountDir(long accountHash)
	{
		Path dir = accountDir(accountHash);
		if (dir == null || !Files.isDirectory(dir))
		{
			return;
		}
		try (Stream<Path> walk = Files.walk(dir))
		{
			walk.sorted(Comparator.reverseOrder()).forEach(path ->
			{
				try
				{
					Files.deleteIfExists(path);
				}
				catch (IOException ex)
				{
					log.debug("Failed deleting {}", path, ex);
				}
			});
		}
		catch (IOException ex)
		{
			log.warn("Failed wiping account profile dir for accountHash={}", ProfileKeyHasher.accountDirName(accountHash), ex);
		}
	}

	private static boolean blank(String value)
	{
		return value == null || value.isBlank();
	}

	/** JSON shape for {@value #SESSION_FILENAME}. */
	public static final class SessionData
	{
		public int schemaVersion;
		public String accessToken;
		public String refreshToken;
		public String accountId;
		public String status;
		public long boundAccountHash = -1L;
	}
}

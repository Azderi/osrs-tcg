package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import com.osrstcg.util.AtomicFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists pending credit-attest events to a per-account JSON file so they survive a client restart
 * before being flushed to the cloud. Reads/writes are file I/O and should not run on the client thread.
 */
@Slf4j
@Singleton
public final class CreditAttestSpillStore
{
	static final String SPILL_FILENAME = "attest-pending.json";

	private final Path profilesRoot;

	@Inject
	CreditAttestSpillStore()
	{
		this(ProfileKeyHasher.profilesRoot());
	}

	CreditAttestSpillStore(Path profilesRoot)
	{
		this.profilesRoot = profilesRoot;
	}

	/** Resolves the spill file path for an account, or {@code null} if the account can't be hashed to a dir name. */
	Path spillFile(long accountHash)
	{
		String id = ProfileKeyHasher.accountDirName(accountHash);
		return id == null ? null : profilesRoot.resolve(id).resolve(SPILL_FILENAME);
	}

	/** Loads the spilled pending events for an account, or an empty list if none/unreadable/invalid. */
	public List<JsonObject> load(long accountHash)
	{
		if (accountHash == -1L)
		{
			return List.of();
		}
		return readSpillFile(spillFile(accountHash));
	}

	/** Reads and parses a spill file as a JSON array of objects; returns an empty list on any failure. */
	private List<JsonObject> readSpillFile(Path file)
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return List.of();
		}
		try
		{
			String json = Files.readString(file, StandardCharsets.UTF_8);
			if (json == null || json.isBlank())
			{
				return List.of();
			}
			JsonElement root = new JsonParser().parse(json);
			if (root == null || !root.isJsonArray())
			{
				log.warn("Credit attest spill is not a JSON array: {}", file);
				return List.of();
			}
			List<JsonObject> out = new ArrayList<>();
			for (JsonElement el : root.getAsJsonArray())
			{
				if (el != null && el.isJsonObject())
				{
					out.add(el.getAsJsonObject());
				}
			}
			return out;
		}
		catch (Exception ex)
		{
			log.warn("Failed reading credit attest spill {}", file, ex);
			return List.of();
		}
	}

	/** Atomically writes {@code events} as the spill file for an account, or deletes it if the list is empty. */
	public void save(long accountHash, List<JsonObject> events)
	{
		if (accountHash == -1L)
		{
			return;
		}
		if (events == null || events.isEmpty())
		{
			delete(accountHash);
			return;
		}
		String id = ProfileKeyHasher.accountDirName(accountHash);
		if (id == null)
		{
			return;
		}
		Path profileDir = profilesRoot.resolve(id);
		Path target = profileDir.resolve(SPILL_FILENAME);
		try
		{
			JsonArray arr = new JsonArray();
			for (JsonObject event : events)
			{
				if (event != null)
				{
					arr.add(event);
				}
			}
			AtomicFiles.writeString(target, arr.toString(), StandardCharsets.UTF_8);
		}
		catch (Exception ex)
		{
			log.debug("Credit attest spill write failed for accountHash={}", accountHash, ex);
		}
	}

	/** Deletes the spill file for an account, if any. Failures are logged and swallowed. */
	public void delete(long accountHash)
	{
		if (accountHash == -1L)
		{
			return;
		}
		try
		{
			Path file = spillFile(accountHash);
			if (file != null)
			{
				Files.deleteIfExists(file);
			}
		}
		catch (Exception ex)
		{
			log.debug("Credit attest spill delete failed for accountHash={}", accountHash, ex);
		}
	}

	/** Deep-copies a list of events so a snapshot can be persisted without aliasing live queue state. */
	static List<JsonObject> copyEvents(List<JsonObject> events)
	{
		if (events == null || events.isEmpty())
		{
			return Collections.emptyList();
		}
		List<JsonObject> copy = new ArrayList<>(events.size());
		for (JsonObject event : events)
		{
			if (event != null)
			{
				copy.add(event.deepCopy());
			}
		}
		return copy;
	}
}

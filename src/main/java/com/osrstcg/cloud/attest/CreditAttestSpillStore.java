package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Durable spill of unacked credit-attest raw events under
 * {@code ~/.runelite/OSRS-TCG/profiles/{sha256(accountHash)}/attest-pending.json}.
 * Survives client crashes between enqueue and successful upload.
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

	/** Visible for tests: {@code profilesRoot} is the parent of per-account profile dirs. */
	CreditAttestSpillStore(Path profilesRoot)
	{
		this.profilesRoot = profilesRoot;
	}

	Path spillFile(long accountHash)
	{
		String id = ProfileKeyHasher.accountDirName(accountHash);
		return id == null ? null : profilesRoot.resolve(id).resolve(SPILL_FILENAME);
	}

	/**
	 * Load spilled events for {@code accountHash}. Missing or corrupt files yield an empty list.
	 */
	public List<JsonObject> load(long accountHash)
	{
		if (accountHash == -1L)
		{
			return List.of();
		}
		return readSpillFile(spillFile(accountHash));
	}

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

	/**
	 * Persist {@code events} for {@code accountHash}. Empty or null lists delete the spill file.
	 * Never throws to callers.
	 */
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
		Path tmp = profileDir.resolve(SPILL_FILENAME + ".tmp");
		try
		{
			Files.createDirectories(profileDir);
			JsonArray arr = new JsonArray();
			for (JsonObject event : events)
			{
				if (event != null)
				{
					arr.add(event);
				}
			}
			Files.writeString(tmp, arr.toString(), StandardCharsets.UTF_8);
			try
			{
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException ex)
			{
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (Exception ex)
		{
			log.debug("Credit attest spill write failed for accountHash={}", accountHash, ex);
			try
			{
				Files.deleteIfExists(tmp);
			}
			catch (Exception ignored)
			{
				// ignore
			}
		}
	}

	/** Delete spill for {@code accountHash}. Never throws. */
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

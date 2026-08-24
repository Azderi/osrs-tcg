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
import net.runelite.client.RuneLite;

/**
 * Durable spill of unacked credit-attest raw events under
 * {@code ~/.runelite/OSRS-TCG/attest/pending-<sha256(accountHash)>.json}.
 * Survives client crashes between enqueue and successful upload.
 */
@Slf4j
@Singleton
public final class CreditAttestSpillStore
{
	private final Path attestDir;

	@Inject
	CreditAttestSpillStore()
	{
		this(Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "attest"));
	}

	/** Visible for tests. */
	CreditAttestSpillStore(Path attestDir)
	{
		this.attestDir = attestDir;
	}

	Path spillFile(long accountHash)
	{
		return attestDir.resolve("pending-" + spillFileName(accountHash) + ".json");
	}

	Path legacySpillFile(long accountHash)
	{
		return attestDir.resolve("pending-" + accountHash + ".json");
	}

	static String spillFileName(long accountHash)
	{
		return ProfileKeyHasher.sha256Hex(Long.toString(accountHash));
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
		List<JsonObject> fromHashed = readSpillFile(spillFile(accountHash));
		if (!fromHashed.isEmpty())
		{
			return fromHashed;
		}
		Path legacy = legacySpillFile(accountHash);
		List<JsonObject> fromLegacy = readSpillFile(legacy);
		if (fromLegacy.isEmpty())
		{
			return fromLegacy;
		}
		save(accountHash, fromLegacy);
		try
		{
			Files.deleteIfExists(legacy);
		}
		catch (Exception ex)
		{
			log.debug("Credit attest legacy spill delete failed for accountHash={}", accountHash, ex);
		}
		return fromLegacy;
	}

	private List<JsonObject> readSpillFile(Path file)
	{
		if (!Files.isRegularFile(file))
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
		Path dir = attestDir;
		String name = spillFileName(accountHash);
		Path target = dir.resolve("pending-" + name + ".json");
		Path tmp = dir.resolve("pending-" + name + ".json.tmp");
		try
		{
			Files.createDirectories(dir);
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
			Files.deleteIfExists(spillFile(accountHash));
			Files.deleteIfExists(legacySpillFile(accountHash));
		}
		catch (Exception ex)
		{
			log.debug("Credit attest spill delete failed for accountHash={}", accountHash, ex);
		}
	}

	/** Test helper: directory that holds spill files. */
	Path getAttestDir()
	{
		return attestDir;
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

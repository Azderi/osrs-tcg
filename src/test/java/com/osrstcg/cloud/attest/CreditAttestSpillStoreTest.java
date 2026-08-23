package com.osrstcg.cloud.attest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CreditAttestSpillStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void saveAndLoadRoundTrip() throws Exception
	{
		Path dir = tmp.newFolder("attest").toPath();
		CreditAttestSpillStore store = new CreditAttestSpillStore(dir);
		long hash = 12345L;

		JsonObject event = new JsonObject();
		event.addProperty("type", "xp_chunk");
		JsonObject evidence = new JsonObject();
		evidence.addProperty("skill", "WOODCUTTING");
		evidence.addProperty("xpDelta", 100);
		event.add("evidence", evidence);
		event.addProperty("at", 1_700_000_000_000L);
		event.addProperty("_optimisticCredits", 5L);

		store.save(hash, List.of(event));
		assertTrue(Files.isRegularFile(store.spillFile(hash)));

		List<JsonObject> loaded = store.load(hash);
		assertEquals(1, loaded.size());
		assertEquals("xp_chunk", loaded.get(0).get("type").getAsString());
		assertEquals(100, loaded.get(0).getAsJsonObject("evidence").get("xpDelta").getAsInt());
		assertEquals(5L, loaded.get(0).get("_optimisticCredits").getAsLong());
	}

	@Test
	public void saveEmptyDeletesSpill() throws Exception
	{
		Path dir = tmp.newFolder("attest").toPath();
		CreditAttestSpillStore store = new CreditAttestSpillStore(dir);
		long hash = 99L;

		JsonObject event = new JsonObject();
		event.addProperty("type", "activity");
		event.add("evidence", new JsonObject());
		event.addProperty("at", 1L);
		store.save(hash, List.of(event));
		assertTrue(Files.isRegularFile(store.spillFile(hash)));

		store.save(hash, List.of());
		assertFalse(Files.exists(store.spillFile(hash)));
	}

	@Test
	public void loadMissingOrCorruptReturnsEmpty() throws Exception
	{
		Path dir = tmp.newFolder("attest").toPath();
		CreditAttestSpillStore store = new CreditAttestSpillStore(dir);
		long hash = 7L;

		assertTrue(store.load(hash).isEmpty());
		assertTrue(store.load(-1L).isEmpty());

		Files.createDirectories(dir);
		Files.writeString(store.spillFile(hash), "{not-an-array}", StandardCharsets.UTF_8);
		assertTrue(store.load(hash).isEmpty());
	}

	@Test
	public void deleteRemovesFile() throws Exception
	{
		Path dir = tmp.newFolder("attest").toPath();
		CreditAttestSpillStore store = new CreditAttestSpillStore(dir);
		long hash = 42L;

		JsonObject event = new JsonObject();
		event.addProperty("type", "npc_kill");
		event.add("evidence", new JsonObject());
		event.addProperty("at", 2L);
		store.save(hash, List.of(event));
		store.delete(hash);
		assertFalse(Files.exists(store.spillFile(hash)));
	}
}

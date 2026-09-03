package com.osrstcg.cloud.attest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CreditAttestQueueAccountIsolationTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void spillFilesStayUnderPerAccountDirs() throws Exception
	{
		Path profilesRoot = tmp.newFolder("profiles").toPath();
		CreditAttestSpillStore store = new CreditAttestSpillStore(profilesRoot);

		JsonObject forA = event("xp_chunk");
		JsonObject forB = event("activity");
		store.save(1L, List.of(forA));
		store.save(2L, List.of(forB));

		assertEquals(
			profilesRoot.resolve(ProfileKeyHasher.accountDirName(1L)).resolve(CreditAttestSpillStore.SPILL_FILENAME),
			store.spillFile(1L));
		assertEquals(
			profilesRoot.resolve(ProfileKeyHasher.accountDirName(2L)).resolve(CreditAttestSpillStore.SPILL_FILENAME),
			store.spillFile(2L));
		assertTrue(Files.isRegularFile(store.spillFile(1L)));
		assertTrue(Files.isRegularFile(store.spillFile(2L)));
		assertFalse(store.spillFile(1L).equals(store.spillFile(2L)));

		assertEquals(1, store.load(1L).size());
		assertEquals("xp_chunk", store.load(1L).get(0).get("type").getAsString());
		assertEquals(1, store.load(2L).size());
		assertEquals("activity", store.load(2L).get(0).get("type").getAsString());
	}

	@Test
	public void savingOneAccountDoesNotOverwriteAnother() throws Exception
	{
		Path profilesRoot = tmp.newFolder("profiles").toPath();
		CreditAttestSpillStore store = new CreditAttestSpillStore(profilesRoot);

		store.save(10L, List.of(event("a")));
		store.save(20L, List.of(event("b")));
		store.save(10L, List.of(event("a2"), event("a3")));

		assertEquals(2, store.load(10L).size());
		assertEquals(1, store.load(20L).size());
		assertEquals("b", store.load(20L).get(0).get("type").getAsString());
	}

	private static JsonObject event(String type)
	{
		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.addProperty("at", 1L);
		event.add("evidence", new JsonObject());
		return event;
	}
}

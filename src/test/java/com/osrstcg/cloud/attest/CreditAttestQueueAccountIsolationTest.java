package com.osrstcg.cloud.attest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CreditAttestQueueAccountIsolationTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void eventAccountHashReadsStamp()
	{
		JsonObject stamped = new JsonObject();
		stamped.addProperty("accountHash", "99");
		assertEquals(99L, CreditAttestQueue.eventAccountHash(stamped));

		assertEquals(-1L, CreditAttestQueue.eventAccountHash(new JsonObject()));
		assertEquals(-1L, CreditAttestQueue.eventAccountHash(null));
	}

	@Test
	public void unstampedLegacyEventsBelongToLoadAccount()
	{
		JsonObject legacy = new JsonObject();
		legacy.addProperty("type", "xp_chunk");
		assertTrue(CreditAttestQueue.eventBelongsToAccount(legacy, 10L));
		assertFalse(CreditAttestQueue.eventBelongsToAccount(legacy, -1L));

		JsonObject foreign = new JsonObject();
		foreign.addProperty("accountHash", "11");
		assertFalse(CreditAttestQueue.eventBelongsToAccount(foreign, 10L));
		assertTrue(CreditAttestQueue.eventBelongsToAccount(foreign, 11L));
	}

	@Test
	public void spillFilesStayUnderPerAccountDirs() throws Exception
	{
		Path profilesRoot = tmp.newFolder("profiles").toPath();
		CreditAttestSpillStore store = new CreditAttestSpillStore(profilesRoot);

		JsonObject forA = eventFor(1L, "xp_chunk");
		JsonObject forB = eventFor(2L, "activity");
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
	public void filteringPendingKeepsOnlyCurrentAccountEvents()
	{
		List<JsonObject> pending = new ArrayList<>();
		pending.add(eventFor(1L, "a"));
		pending.add(eventFor(2L, "b"));
		pending.add(eventFor(1L, "c"));

		long current = 1L;
		List<JsonObject> forCurrent = new ArrayList<>();
		List<JsonObject> foreign = new ArrayList<>();
		for (JsonObject event : pending)
		{
			if (CreditAttestQueue.eventBelongsToAccount(event, current))
			{
				forCurrent.add(event);
			}
			else
			{
				foreign.add(event);
			}
		}

		assertEquals(2, forCurrent.size());
		assertEquals(1, foreign.size());
		assertEquals("b", foreign.get(0).get("type").getAsString());
	}

	private static JsonObject eventFor(long accountHash, String type)
	{
		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.addProperty("accountHash", Long.toString(accountHash));
		event.addProperty("at", 1L);
		event.add("evidence", new JsonObject());
		return event;
	}
}

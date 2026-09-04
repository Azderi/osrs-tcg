package com.osrstcg.cloud.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstcg.cloud.session.CloudSessionFileStore.SessionData;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CloudSessionFileStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void saveLoadRoundTrip() throws Exception
	{
		CloudSessionFileStore store = newStore();
		SessionData data = session("access-a", "refresh-a", "acct-1", "ok");

		store.save(42L, data);
		SessionData loaded = store.load(42L);

		assertNotNull(loaded);
		assertEquals("access-a", loaded.accessToken);
		assertEquals("refresh-a", loaded.refreshToken);
		assertEquals("acct-1", loaded.accountId);
		assertEquals("ok", loaded.status);
		assertEquals(42L, loaded.boundAccountHash);
		assertTrue(Files.isRegularFile(store.sessionFile(42L)));
	}

	@Test
	public void accountsAreIsolated() throws Exception
	{
		CloudSessionFileStore store = newStore();
		store.save(1L, session("a1", "r1", null, null));
		store.save(2L, session("a2", "r2", null, null));

		assertEquals("a1", store.load(1L).accessToken);
		assertEquals("a2", store.load(2L).accessToken);
		assertFalse(store.sessionFile(1L).equals(store.sessionFile(2L)));
	}

	@Test
	public void deleteRemovesSessionFile() throws Exception
	{
		CloudSessionFileStore store = newStore();
		store.save(7L, session("a", "r", null, null));
		assertTrue(Files.isRegularFile(store.sessionFile(7L)));

		store.delete(7L);
		assertNull(store.load(7L));
		assertFalse(Files.isRegularFile(store.sessionFile(7L)));
	}

	@Test
	public void deleteAccountDirWipesFolder() throws Exception
	{
		CloudSessionFileStore store = newStore();
		store.save(9L, session("a", "r", null, null));
		Path dir = store.accountDir(9L);
		Files.writeString(dir.resolve("tcg.save"), "blob");
		assertTrue(Files.isDirectory(dir));

		store.deleteAccountDir(9L);
		assertFalse(Files.exists(dir));
		assertNull(store.load(9L));
	}

	@Test
	public void loadReturnsNullForMissingOrInvalid() throws Exception
	{
		CloudSessionFileStore store = newStore();
		assertNull(store.load(99L));
		assertNull(store.load(-1L));

		Path file = store.sessionFile(5L);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{not-json");
		assertNull(store.load(5L));
	}

	private CloudSessionFileStore newStore() throws Exception
	{
		return new CloudSessionFileStore(tmp.newFolder("profiles").toPath());
	}

	private static SessionData session(String access, String refresh, String accountId, String status)
	{
		SessionData data = new SessionData();
		data.accessToken = access;
		data.refreshToken = refresh;
		data.accountId = accountId;
		data.status = status;
		return data;
	}
}

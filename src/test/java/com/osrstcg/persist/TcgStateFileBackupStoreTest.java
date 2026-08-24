package com.osrstcg.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TcgStateFileBackupStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void gameplaySaveDirectoryUsesProfilesRoot() throws Exception
	{
		Path profilesRoot = tmp.newFolder("profiles").toPath();
		Path legacyRoot = tmp.newFolder("backups").toPath();
		long accountHash = 12345L;
		TestFileStore store = new TestFileStore(profilesRoot, legacyRoot, accountHash);

		Path dir = store.saveDirectory();
		assertNotNull(dir);
		assertEquals(profilesRoot.resolve(ProfileKeyHasher.accountDirName(accountHash)), dir);
	}

	@Test
	public void legacySaveDirectoryUsesBackupsRoot() throws Exception
	{
		Path profilesRoot = tmp.newFolder("profiles").toPath();
		Path legacyRoot = tmp.newFolder("backups").toPath();
		TestFileStore store = new TestFileStore(profilesRoot, legacyRoot, 12345L);

		Path dir = store.saveDirectory("default");
		assertNotNull(dir);
		assertEquals(legacyRoot.resolve("default"), dir);
	}

	@Test
	public void legacyScanIncludesAccountHashDirUnderBackups() throws Exception
	{
		Path profilesRoot = tmp.newFolder("profiles").toPath();
		Path legacyRoot = tmp.newFolder("backups").toPath();
		long accountHash = 12345L;
		String dirName = ProfileKeyHasher.accountDirName(accountHash);
		Path legacyAccountDir = legacyRoot.resolve(dirName);
		Files.createDirectories(legacyAccountDir);
		Files.writeString(legacyAccountDir.resolve(TcgStateFileBackupStore.MASTER_FILENAME), "test");

		TestFileStore store = new TestFileStore(profilesRoot, legacyRoot, accountHash);
		assertTrue(store.hasLegacySaveFiles());
	}

	private static final class TestFileStore extends TcgStateFileBackupStore
	{
		private final Path profilesRoot;
		private final Path legacyRoot;
		private final long accountHash;

		TestFileStore(Path profilesRoot, Path legacyRoot, long accountHash)
		{
			super(null, new TcgStateCodec(new Gson()), new Gson());
			this.profilesRoot = profilesRoot;
			this.legacyRoot = legacyRoot;
			this.accountHash = accountHash;
		}

		@Override
		long resolveAccountHashForIo()
		{
			return accountHash;
		}

		@Override
		Path profilesRoot()
		{
			return profilesRoot;
		}

		@Override
		Path legacyBackupsRoot()
		{
			return legacyRoot;
		}
	}
}

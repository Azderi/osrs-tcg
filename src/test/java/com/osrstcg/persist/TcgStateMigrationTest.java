package com.osrstcg.persist;

import com.google.gson.Gson;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.TcgState;
import com.osrstcg.service.TcgStateService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies hybrid migration: schema-5 (0.16.2) and schema-6 config blobs seed disk,
 * obsolete backup keys are unset, and load priority is config → tcg.save → snapshot.
 */
public class TcgStateMigrationTest
{
	private static final String SCHEMA_5_JSON = ""
		+ "{"
		+ "\"schemaVersion\":5,"
		+ "\"credits\":777,"
		+ "\"openedPacks\":2,"
		+ "\"cardInstances\":["
		+ "{\"id\":\"s5\",\"cardName\":\"Dragon dagger\",\"foil\":false,\"pulledBy\":\"Mig\",\"pulledAt\":1000}"
		+ "],"
		+ "\"totalCreditsGained\":900,"
		+ "\"profileCreatedAtUnix\":1700000000,"
		+ "\"profileSavedAtUnix\":1700000100,"
		+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0}"
		+ "}";

	private final Gson gson = new Gson();
	private final TcgStateCodec codec = new TcgStateCodec(gson);
	private final Map<String, String> profile = new HashMap<>();
	private final Map<String, String> global = new HashMap<>();
	private Path tempDir;
	private TempDirFileStore diskStore;
	private TestableTcgStateStore store;

	@Before
	public void setUp() throws IOException
	{
		profile.clear();
		global.clear();
		tempDir = Files.createTempDirectory("osrs-tcg-migration-");
		diskStore = new TempDirFileStore(codec, gson, tempDir);
		store = new TestableTcgStateStore(codec, diskStore, profile, global);
	}

	@After
	public void tearDown() throws IOException
	{
		if (tempDir != null && Files.isDirectory(tempDir))
		{
			try (Stream<Path> walk = Files.walk(tempDir))
			{
				walk.sorted(Comparator.reverseOrder()).forEach(path ->
				{
					try
					{
						Files.deleteIfExists(path);
					}
					catch (IOException ignored)
					{
					}
				});
			}
		}
	}

	@Test
	public void migratesSchema5ConfigBlobToDiskAndUnsetsObsoleteKeys()
	{
		putEncodedConfig(SCHEMA_5_JSON);
		profile.put("stateBackup", profile.get("state"));
		profile.put("hashBackup", profile.get("hash"));
		profile.put("stateWrittenAt", "1710000000000");

		TcgStateLoadResult result = store.load();

		Assert.assertEquals(TcgStateLoadSource.CONFIG, result.getSource());
		Assert.assertEquals(777L, result.getState().getEconomyState().getCredits());
		Assert.assertEquals(1, result.getState().getCollectionState().getOwnedInstances().size());
		Assert.assertEquals("Dragon dagger",
			result.getState().getCollectionState().getOwnedInstances().get(0).getCardName());

		Assert.assertNull(profile.get("stateBackup"));
		Assert.assertNull(profile.get("hashBackup"));
		Assert.assertNull(profile.get("stateWrittenAt"));
		Assert.assertNotNull(profile.get("state"));
		Assert.assertNotNull(profile.get("hash"));

		Assert.assertTrue(Files.isRegularFile(tempDir.resolve(TcgStateFileBackupStore.MASTER_FILENAME)));
		Assert.assertTrue(Files.isRegularFile(tempDir.resolve(TcgStateFileBackupStore.SAVES_INDEX_FILENAME)));

		TcgState master = diskStore.loadMaster().orElseThrow();
		Assert.assertEquals(TcgState.CURRENT_SCHEMA_VERSION, master.getSchemaVersion());
		Assert.assertEquals(777L, master.getEconomyState().getCredits());
		Assert.assertEquals(1, master.getCollectionState().getOwnedInstances().size());

		String masterJson = codec.toJson(master);
		Assert.assertTrue(masterJson.contains("cardEntries"));
		Assert.assertFalse(masterJson.contains("cardInstances"));
	}

	@Test
	public void migratesSchema6ConfigBlobToDisk()
	{
		String schema6 = ""
			+ "{"
			+ "\"schemaVersion\":6,"
			+ "\"credits\":55,"
			+ "\"openedPacks\":1,"
			+ "\"cardEntries\":[{\"cardName\":\"Abyssal whip\",\"variants\":["
			+ "{\"pulledBy\":\"P\",\"pulledAt\":1}"
			+ "]}],"
			+ "\"totalCreditsGained\":55,"
			+ "\"profileCreatedAtUnix\":1,"
			+ "\"profileSavedAtUnix\":2,"
			+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0}"
			+ "}";
		putEncodedConfig(schema6);

		TcgStateLoadResult result = store.load();
		Assert.assertEquals(TcgStateLoadSource.CONFIG, result.getSource());
		Assert.assertEquals(55L, result.getState().getEconomyState().getCredits());
		Assert.assertTrue(diskStore.loadMaster().isPresent());
		Assert.assertEquals(55L, diskStore.loadMaster().get().getEconomyState().getCredits());
	}

	@Test
	public void seedsFromStateBackupWhenPrimaryMissing()
	{
		String blob = TcgStateStorageEncoding.encode(SCHEMA_5_JSON);
		String hash = TcgStateHash.hexOfUtf8(blob);
		profile.put("stateBackup", blob);
		profile.put("hashBackup", hash);
		profile.put("stateWrittenAt", "1");

		TcgStateLoadResult result = store.load();
		Assert.assertEquals(TcgStateLoadSource.DISK, result.getSource());
		Assert.assertEquals(777L, result.getState().getEconomyState().getCredits());
		Assert.assertNull(profile.get("stateBackup"));
		Assert.assertTrue(diskStore.loadMaster().isPresent());
	}

	@Test
	public void doesNotReseedWhenMasterAlreadyExists() throws IOException
	{
		String oldMasterJson = "{"
			+ "\"schemaVersion\":6,\"credits\":1,\"openedPacks\":0,\"cardEntries\":[],"
			+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0},"
			+ "\"totalCreditsGained\":0,\"profileCreatedAtUnix\":1,\"profileSavedAtUnix\":1"
			+ "}";
		String oldBlob = TcgStateStorageEncoding.encode(oldMasterJson);
		diskStore.writeMaster(oldBlob, 0, 0L, TcgSaveTrigger.UNKNOWN);

		putEncodedConfig(SCHEMA_5_JSON);

		TcgStateLoadResult result = store.load();
		Assert.assertEquals(TcgStateLoadSource.CONFIG, result.getSource());
		Assert.assertEquals(777L, result.getState().getEconomyState().getCredits());

		TcgState master = diskStore.loadMaster().orElseThrow();
		Assert.assertEquals(1L, master.getEconomyState().getCredits());
	}

	@Test
	public void saveConfigOnlyWritesStateAndHash()
	{
		String schema6 = "{"
			+ "\"schemaVersion\":6,\"credits\":33,\"openedPacks\":1,\"cardEntries\":[],"
			+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0},"
			+ "\"totalCreditsGained\":33,\"profileCreatedAtUnix\":1,\"profileSavedAtUnix\":1"
			+ "}";
		TcgState state = codec.fromJson(schema6);
		Assert.assertTrue(store.saveConfigOnly(state));
		Assert.assertNotNull(profile.get("state"));
		Assert.assertNotNull(profile.get("hash"));
		Assert.assertEquals(profile.get("hash"), TcgStateHash.hexOfUtf8(profile.get("state")));
		TcgState roundTrip = codec.fromJson(TcgStateStorageEncoding.decode(profile.get("state")));
		Assert.assertEquals(33L, roundTrip.getEconomyState().getCredits());
	}

	@Test
	public void loadFallsBackToMasterWhenConfigMissing()
	{
		String blob = TcgStateStorageEncoding.encode(SCHEMA_5_JSON);
		diskStore.writeMaster(blob, 1, 777L, TcgSaveTrigger.MIGRATION);

		TcgStateLoadResult result = store.load();
		Assert.assertEquals(TcgStateLoadSource.DISK, result.getSource());
		Assert.assertEquals(777L, result.getState().getEconomyState().getCredits());
	}

	@Test
	public void loadFallsBackToSnapshotWhenConfigAndMasterMissing()
	{
		String blob = TcgStateStorageEncoding.encode(SCHEMA_5_JSON);
		diskStore.writeSnapshot(blob, 1, TcgSaveTrigger.LOGOUT);

		TcgStateLoadResult result = store.load();
		Assert.assertEquals(TcgStateLoadSource.DISK_SNAPSHOT, result.getSource());
		Assert.assertEquals(777L, result.getState().getEconomyState().getCredits());
	}

	@Test
	public void loadPrefersConfigOverNewerDiskMaster()
	{
		putEncodedConfig(SCHEMA_5_JSON);

		String other = "{"
			+ "\"schemaVersion\":6,\"credits\":999,\"openedPacks\":0,\"cardEntries\":[],"
			+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0},"
			+ "\"totalCreditsGained\":0,\"profileCreatedAtUnix\":1,\"profileSavedAtUnix\":1"
			+ "}";
		diskStore.writeMaster(TcgStateStorageEncoding.encode(other), 0, 999L, TcgSaveTrigger.COLLECTION_CHANGE);

		TcgStateLoadResult result = store.load();
		Assert.assertEquals(TcgStateLoadSource.CONFIG, result.getSource());
		Assert.assertEquals(777L, result.getState().getEconomyState().getCredits());
	}

	@Test
	public void staleConfigDoesNotShadowStrictlyNewerDiskSave()
	{
		// Config is only written on logout/shutdown/unload, so it can lag hours behind
		// the throttled disk snapshots; the newer disk state must win on load.
		putEncodedConfig(SCHEMA_5_JSON); // profileSavedAtUnix = 1700000100

		String newer = "{"
			+ "\"schemaVersion\":6,\"credits\":999,\"openedPacks\":9,"
			+ "\"cardEntries\":["
			+ "{\"cardName\":\"Abyssal whip\",\"variants\":[{\"pulledBy\":\"P\",\"pulledAt\":1}]},"
			+ "{\"cardName\":\"Dragon dagger\",\"variants\":[{\"pulledBy\":\"P\",\"pulledAt\":2}]}"
			+ "],"
			+ "\"totalCreditsGained\":999,"
			+ "\"profileCreatedAtUnix\":1700000000,"
			+ "\"profileSavedAtUnix\":1700040000,"
			+ "\"skillCreditBaseline\":{\"skillXp\":{},\"uncreditedXp\":0}"
			+ "}";
		diskStore.writeSnapshot(TcgStateStorageEncoding.encode(newer), 2, 999L, TcgSaveTrigger.LOGOUT);

		TcgStateLoadResult result = store.load();

		Assert.assertNotEquals(TcgStateLoadSource.CONFIG, result.getSource());
		Assert.assertEquals(999L, result.getState().getEconomyState().getCredits());
		Assert.assertEquals(2, result.getState().getCollectionState().getOwnedInstances().size());

		// The migration seed must also come from the freshest source, not the stale config.
		TcgState master = diskStore.loadMaster().orElseThrow();
		Assert.assertEquals(999L, master.getEconomyState().getCredits());
	}

	@Test
	public void crashAfterCollectionChangeRestoresMasterOverStaleConfig()
	{
		// Collection changes persist only tcg.save; config is written on checkpoints.
		// For the stale-config recovery above to ever fire in practice, the master
		// write must advance profileSavedAtUnix past the config blob's — otherwise an
		// abnormal exit (crash, kill, power loss) rolls the session back to the stale
		// config, and the next master save then overwrites tcg.save from that rolled-back
		// state, losing the session's cards permanently.
		putEncodedConfig(SCHEMA_5_JSON); // profileSavedAtUnix = 1700000100

		TcgStateService service = new TcgStateService(store, false, null, null, null);
		service.load();
		service.addOwnedCardInstance(
			new OwnedCardInstance("crash-1", "Abyssal whip", false, "Player", 1710000000000L));
		// Process dies here: no logout/shutdown checkpoint runs.

		TestableTcgStateStore freshStore = new TestableTcgStateStore(codec, diskStore, profile, global);
		TcgStateLoadResult result = freshStore.load();

		Assert.assertEquals(TcgStateLoadSource.DISK, result.getSource());
		Assert.assertEquals(2, result.getState().getCollectionState().getOwnedInstances().size());
	}

	private void putEncodedConfig(String plainJson)
	{
		String blob = TcgStateStorageEncoding.encode(plainJson);
		profile.put("state", blob);
		profile.put("hash", TcgStateHash.hexOfUtf8(blob));
	}

	private static final class TempDirFileStore extends TcgStateFileBackupStore
	{
		private final Path dir;

		private TempDirFileStore(TcgStateCodec codec, Gson gson, Path dir)
		{
			super(null, codec, gson);
			this.dir = dir;
		}

		@Override
		Path saveDirectory()
		{
			return dir;
		}

		@Override
		Path saveDirectory(String profileDirId)
		{
			return dir;
		}

		@Override
		public String currentProfileDirName()
		{
			return dir.getFileName().toString();
		}
	}

	private static final class TestableTcgStateStore extends TcgStateStore
	{
		private final Map<String, String> profile;
		private final Map<String, String> global;

		private TestableTcgStateStore(
			TcgStateCodec codec,
			TcgStateFileBackupStore diskStore,
			Map<String, String> profile,
			Map<String, String> global)
		{
			super(null, codec, diskStore);
			this.profile = profile;
			this.global = global;
		}

		@Override
		void writeProfileScoped(String key, String value)
		{
			profile.put(key, value);
		}

		@Override
		String getProfileScoped(String key)
		{
			return profile.get(key);
		}

		@Override
		void unsetProfileScoped(String key)
		{
			profile.remove(key);
		}

		@Override
		String getGlobalScoped(String key)
		{
			return global.get(key);
		}

		@Override
		void writeGlobalScoped(String key, String value)
		{
			global.put(key, value);
		}

		@Override
		void unsetGlobalScoped(String key)
		{
			global.remove(key);
		}

		@Override
		void moveOldStateIntoProfile()
		{
			// Covered separately; keep migration tests focused on disk seed + obsolete keys.
		}
	}
}

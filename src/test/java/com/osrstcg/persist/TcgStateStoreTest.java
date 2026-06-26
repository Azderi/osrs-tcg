package com.osrstcg.persist;

import com.google.gson.Gson;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.EconomyState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.RewardTuningState;
import com.osrstcg.model.TcgState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TcgStateStoreTest
{
	private final Map<String, String> config = new HashMap<>();
	private final TcgStateCodec codec = new TcgStateCodec(new Gson());
	private TestableTcgStateStore store;

	@Before
	public void setUp()
	{
		config.clear();
		store = new TestableTcgStateStore(codec, config);
	}

	@Test
	public void saveWritesBackupAfterSuccessfulPrimarySave()
	{
		TcgState state = stateWithCard("Abyssal whip");
		store.save(state);

		Assert.assertNotNull(config.get("state"));
		Assert.assertNotNull(config.get("hash"));
		Assert.assertEquals(config.get("state"), config.get("stateBackup"));
		Assert.assertEquals(config.get("hash"), config.get("hashBackup"));
	}

	@Test
	public void loadRestoresFromBackupWhenPrimaryHashMismatches()
	{
		TcgState state = stateWithCard("Dragon dagger");
		store.save(state);

		config.put("hash", "deadbeef");

		TcgState loaded = store.load();
		Assert.assertEquals(1, loaded.getCollectionState().getOwnedInstances().size());
		Assert.assertEquals("Dragon dagger", loaded.getCollectionState().getOwnedInstances().get(0).getCardName());
	}

	@Test
	public void loadUsesPrimaryWhenValidEvenIfBackupExists()
	{
		TcgState backupState = stateWithCard("Old card");
		store.save(backupState);

		TcgState primaryState = stateWithCard("Current card");
		String json = codec.toJson(primaryState);
		String stored = TcgStateStorageEncoding.encode(json);
		config.put("state", stored);
		config.put("hash", TcgStateHash.hexOfUtf8(stored));

		TcgState loaded = store.load();
		Assert.assertEquals("Current card", loaded.getCollectionState().getOwnedInstances().get(0).getCardName());
	}

	@Test
	public void loadReturnsEmptyWhenPrimaryAndBackupMissing()
	{
		TcgState loaded = store.load();
		Assert.assertTrue(loaded.getCollectionState().getOwnedCards().isEmpty());
	}

	@Test
	public void loadRestoresFromBackupWhenPrimaryMissing()
	{
		TcgState backupState = stateWithCard("Backup only");
		store.save(backupState);
		config.remove("state");
		config.remove("hash");

		TcgState loaded = store.load();
		Assert.assertEquals("Backup only", loaded.getCollectionState().getOwnedInstances().get(0).getCardName());
	}

	private static TcgState stateWithCard(String cardName)
	{
		return new TcgState(
			TcgState.CURRENT_SCHEMA_VERSION,
			new EconomyState(100L, 1L),
			CollectionState.copyOf(List.of(OwnedCardInstance.createNew(cardName, false, "Tester", 1L))),
			RewardTuningState.DEFAULTS,
			false,
			1.0d,
			0,
			0
		);
	}

	private static final class TestableTcgStateStore extends TcgStateStore
	{
		private final Map<String, String> config;

		private TestableTcgStateStore(TcgStateCodec codec, Map<String, String> config)
		{
			super(null, codec);
			this.config = config;
		}

		@Override
		void writeProfileScoped(String key, String value)
		{
			config.put(key, value);
		}

		@Override
		String getProfileScoped(String key)
		{
			return config.get(key);
		}
	}
}

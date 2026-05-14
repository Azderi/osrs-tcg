package com.runelitetcg.persist;

import com.google.gson.Gson;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.CollectionState;
import com.runelitetcg.model.EconomyState;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.model.TcgState;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class TcgStateCodecTest
{
	private final TcgStateCodec codec = new TcgStateCodec(new Gson());

	@Test
	public void fromJsonReturnsDefaultsWhenMissing()
	{
		TcgState state = codec.fromJson(null);
		Assert.assertEquals(0L, state.getEconomyState().getCredits());
		Assert.assertEquals(0L, state.getEconomyState().getOpenedPacks());
		Assert.assertTrue(state.getCollectionState().getOwnedCards().isEmpty());
		Assert.assertEquals(TcgState.CURRENT_SCHEMA_VERSION, state.getSchemaVersion());
	}

	@Test
	public void fromJsonReturnsDefaultsWhenMalformed()
	{
		TcgState state = codec.fromJson("not-json");
		Assert.assertEquals(0L, state.getEconomyState().getCredits());
		Assert.assertTrue(state.getCollectionState().getOwnedCards().isEmpty());
	}

	@Test
	public void toJsonAndFromJsonRoundTripState()
	{
		Map<CardCollectionKey, Integer> collection = new HashMap<>();
		collection.put(new CardCollectionKey("Abyssal whip", false), 2);
		collection.put(new CardCollectionKey("Abyssal whip", true), 1);

		TcgState source = new TcgState(
			TcgState.CURRENT_SCHEMA_VERSION,
			new EconomyState(1500L, 7L),
			new CollectionState(collection),
			new RewardTuningState(5, 1.25d, 1.5d, 2.0d),
			true,
			1.15d
		);

		String json = codec.toJson(source);
		TcgState loaded = codec.fromJson(json);

		Assert.assertEquals(1500L, loaded.getEconomyState().getCredits());
		Assert.assertEquals(7L, loaded.getEconomyState().getOpenedPacks());
		Assert.assertEquals(Integer.valueOf(2), loaded.getCollectionState().getOwnedCards().get(new CardCollectionKey("Abyssal whip", false)));
		Assert.assertEquals(Integer.valueOf(1), loaded.getCollectionState().getOwnedCards().get(new CardCollectionKey("Abyssal whip", true)));
		Assert.assertEquals(5, loaded.getRewardTuning().getFoilChancePercent());
		Assert.assertEquals(1.25d, loaded.getRewardTuning().getKillCreditMultiplier(), 0.0001d);
		Assert.assertEquals(1.5d, loaded.getRewardTuning().getLevelUpCreditMultiplier(), 0.0001d);
		Assert.assertEquals(2.0d, loaded.getRewardTuning().getXpCreditMultiplier(), 0.0001d);
		Assert.assertTrue(loaded.isDebugLogging());
		Assert.assertEquals(1.15d, loaded.getPackRevealOverlayScale(), 0.0001d);
	}
}

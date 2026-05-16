package com.runelitetcg.service;

import com.google.gson.Gson;
import com.runelitetcg.data.BoosterPackDefinition;
import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.model.CollectionState;
import com.runelitetcg.model.EconomyState;
import com.runelitetcg.model.PackOpenResult;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.model.TcgState;
import java.util.Arrays;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

public class PackOpeningServiceTest
{
	@Test
	public void shouldFailWhenInsufficientCredits()
	{
		CardDatabase cardDatabase = testCardDatabase();
		TcgStateService stateService = new TcgStateService(new TcgState(2, new EconomyState(1000, 0), CollectionState.empty(), RewardTuningState.DEFAULTS, false, 1.0d, 0, 0));
		PackOpeningService service = new PackOpeningService(cardDatabase, stateService, null, null, null, new Random(42));
		BoosterPackDefinition booster = testBooster();

		PackOpenResult result = service.buyAndOpenPack(booster);
		Assert.assertFalse(result.isSuccess());
		Assert.assertEquals(1000L, stateService.getCredits());
	}

	@Test
	public void shouldDeductPackPriceAndAddFiveCards()
	{
		CardDatabase cardDatabase = testCardDatabase();
		TcgStateService stateService = new TcgStateService(new TcgState(2, new EconomyState(5000, 0), CollectionState.empty(), RewardTuningState.DEFAULTS, false, 1.0d, 0, 0));
		PackOpeningService service = new PackOpeningService(cardDatabase, stateService, null, null, null, new Random(42));
		BoosterPackDefinition booster = testBooster();

		PackOpenResult result = service.buyAndOpenPack(booster);
		Assert.assertTrue(result.isSuccess());
		Assert.assertEquals(5, result.getPulls().size());
		Assert.assertEquals(2500L, result.getCreditsAfter());
		Assert.assertEquals(2500L, stateService.getCredits());
		Assert.assertEquals(1L, stateService.getState().getEconomyState().getOpenedPacks());
	}

	private static BoosterPackDefinition testBooster()
	{
		BoosterPackDefinition booster = new BoosterPackDefinition();
		booster.setId("test");
		booster.setName("Weapon Set");
		booster.setCategory(Arrays.asList("Weapon"));
		booster.setPrice(2500);
		return booster;
	}

	private CardDatabase testCardDatabase()
	{
		CardDatabase database = new CardDatabase(new Gson());
		CardDefinition a = new CardDefinition();
		a.setName("Abyssal whip");
		a.setCategory(Arrays.asList("Weapon"));
		a.setQuestItem(false);
		CardDefinition b = new CardDefinition();
		b.setName("Dragon scimitar");
		b.setCategory(Arrays.asList("Weapon"));
		b.setQuestItem(false);
		database.setCardsForTesting(Arrays.asList(a, b));
		return database;
	}
}

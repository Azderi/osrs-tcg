package com.osrstcg.data;

import com.google.gson.Gson;
import com.osrstcg.service.RarityMath;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * Characterizes that {@link CardDatabase#displayTiersByCardName()} always equals a fresh
 * {@link RarityMath#displayTierByCardName(List)} over the current cards — the invariant that
 * lets pack open/reveal reuse the cached map instead of recomputing it.
 */
public class CardDatabaseTierCacheTest
{
	@Test
	public void cachedTiersMatchFreshComputationAfterLoad()
	{
		CardDatabase db = new CardDatabase(new Gson());
		db.load();

		Map<String, RarityMath.Tier> fresh = RarityMath.displayTierByCardName(db.getCards());

		Assert.assertFalse(fresh.isEmpty());
		Assert.assertEquals(fresh, db.displayTiersByCardName());
	}

	@Test
	public void cachedTiersRebuiltAfterSetCardsForTesting()
	{
		CardDatabase db = new CardDatabase(new Gson());
		db.load();
		List<CardDefinition> subset = db.getCards().subList(0, 50);

		db.setCardsForTesting(subset);
		Map<String, RarityMath.Tier> fresh = RarityMath.displayTierByCardName(db.getCards());

		Assert.assertFalse(fresh.isEmpty());
		Assert.assertEquals(fresh, db.displayTiersByCardName());
	}
}

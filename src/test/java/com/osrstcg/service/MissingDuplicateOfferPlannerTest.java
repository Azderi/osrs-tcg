package com.osrstcg.service;

import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.OwnedCardInstance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class MissingDuplicateOfferPlannerTest
{
	@Test
	public void selectOfferableDuplicatesRequiresTwoCopiesAndUnlockedSpare()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("a1", "Whip", false, false, 1L),
			inst("a2", "Whip", false, false, 2L),
			inst("b1", "Scim", false, false, 1L),
			inst("c1", "Lock", false, true, 1L),
			inst("c2", "Lock", false, true, 2L),
			inst("d1", "Mixed", false, true, 1L),
			inst("d2", "Mixed", false, false, 2L));

		List<OwnedCardInstance> offerable = MissingDuplicateOfferPlanner.selectOfferableDuplicates(owned, Set.of());
		Assert.assertEquals(2, offerable.size());
		Assert.assertEquals("Mixed", offerable.get(0).getCardName());
		Assert.assertEquals("d2", offerable.get(0).getInstanceId());
		Assert.assertEquals("Whip", offerable.get(1).getCardName());
		Assert.assertEquals("a1", offerable.get(1).getInstanceId());
	}

	@Test
	public void selectOfferableDuplicatesTreatsFoilAsSeparateVariant()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("n1", "Whip", false, false, 1L),
			inst("n2", "Whip", false, false, 2L),
			inst("f1", "Whip", true, false, 3L));

		List<OwnedCardInstance> offerable = MissingDuplicateOfferPlanner.selectOfferableDuplicates(owned, Set.of());
		Assert.assertEquals(1, offerable.size());
		Assert.assertFalse(offerable.get(0).isFoil());
	}

	@Test
	public void selectOfferableDuplicatesSkipsAlreadyOffered()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("a1", "Whip", false, false, 1L),
			inst("a2", "Whip", false, false, 2L));

		List<OwnedCardInstance> offerable =
			MissingDuplicateOfferPlanner.selectOfferableDuplicates(owned, Set.of("a1"));
		Assert.assertEquals(1, offerable.size());
		Assert.assertEquals("a2", offerable.get(0).getInstanceId());
	}

	@Test
	public void filterToMissingKeepsOnlyRequestedKeys()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("a1", "Whip", false, false, 1L),
			inst("b1", "Scim", true, false, 1L),
			inst("b2", "Scim", true, false, 2L));
		List<OwnedCardInstance> candidates = List.of(
			inst("a1", "Whip", false, false, 1L),
			inst("b1", "Scim", true, false, 1L));
		Set<CardCollectionKey> missing = Set.of(new CardCollectionKey("Scim", true));

		List<OwnedCardInstance> filtered =
			MissingDuplicateOfferPlanner.filterToMissing(candidates, missing, owned, Set.of());
		Assert.assertEquals(1, filtered.size());
		Assert.assertEquals("b1", filtered.get(0).getInstanceId());
	}

	@Test
	public void filterToMissingNeverIncludesLockedCards()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("a1", "Whip", false, true, 1L),
			inst("a2", "Whip", false, true, 2L),
			inst("b1", "Scim", true, false, 1L),
			inst("b2", "Scim", true, false, 2L));
		List<OwnedCardInstance> candidates = List.of(
			inst("a1", "Whip", false, true, 1L),
			inst("b1", "Scim", true, false, 1L));
		Set<CardCollectionKey> missing = Set.of(
			new CardCollectionKey("Whip", false),
			new CardCollectionKey("Scim", true));

		List<OwnedCardInstance> filtered =
			MissingDuplicateOfferPlanner.filterToMissing(candidates, missing, owned, Set.of());
		Assert.assertEquals(1, filtered.size());
		Assert.assertEquals("b1", filtered.get(0).getInstanceId());
		Assert.assertFalse(filtered.get(0).isLocked());
	}

	@Test
	public void preferNormalOverFoilDropsFoilWhenNormalSpareExists()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("n1", "Whip", false, false, 1L),
			inst("n2", "Whip", false, false, 2L),
			inst("f1", "Whip", true, false, 3L),
			inst("f2", "Whip", true, false, 4L));
		List<OwnedCardInstance> candidates = List.of(
			inst("n1", "Whip", false, false, 1L),
			inst("f1", "Whip", true, false, 3L));

		List<OwnedCardInstance> preferred =
			MissingDuplicateOfferPlanner.preferNormalOverFoil(candidates, owned, Set.of());
		Assert.assertEquals(1, preferred.size());
		Assert.assertFalse(preferred.get(0).isFoil());
		Assert.assertEquals("n1", preferred.get(0).getInstanceId());
	}

	@Test
	public void preferNormalOverFoilKeepsFoilWhenNoNormalSpare()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("n1", "Whip", false, false, 1L),
			inst("f1", "Whip", true, false, 2L),
			inst("f2", "Whip", true, false, 3L));
		List<OwnedCardInstance> candidates = List.of(
			inst("f1", "Whip", true, false, 2L));

		List<OwnedCardInstance> preferred =
			MissingDuplicateOfferPlanner.preferNormalOverFoil(candidates, owned, Set.of());
		Assert.assertEquals(1, preferred.size());
		Assert.assertTrue(preferred.get(0).isFoil());
	}

	@Test
	public void preferNormalOverFoilKeepsFoilWhenNormalsAreLocked()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("n1", "Whip", false, true, 1L),
			inst("n2", "Whip", false, true, 2L),
			inst("f1", "Whip", true, false, 3L),
			inst("f2", "Whip", true, false, 4L));
		List<OwnedCardInstance> candidates = List.of(
			inst("f1", "Whip", true, false, 3L));

		List<OwnedCardInstance> preferred =
			MissingDuplicateOfferPlanner.preferNormalOverFoil(candidates, owned, Set.of());
		Assert.assertEquals(1, preferred.size());
		Assert.assertTrue(preferred.get(0).isFoil());
	}

	@Test
	public void selectOfferableDuplicatesRequiresTwoUnlockedFoils()
	{
		List<OwnedCardInstance> onlyOneUnlocked = List.of(
			inst("f1", "Whip", true, true, 1L),
			inst("f2", "Whip", true, false, 2L));
		Assert.assertTrue(MissingDuplicateOfferPlanner.selectOfferableDuplicates(onlyOneUnlocked, Set.of()).isEmpty());

		List<OwnedCardInstance> twoUnlocked = List.of(
			inst("f1", "Whip", true, false, 1L),
			inst("f2", "Whip", true, false, 2L));
		List<OwnedCardInstance> offerable =
			MissingDuplicateOfferPlanner.selectOfferableDuplicates(twoUnlocked, Set.of());
		Assert.assertEquals(1, offerable.size());
		Assert.assertTrue(offerable.get(0).isFoil());
		Assert.assertEquals("f1", offerable.get(0).getInstanceId());
	}

	@Test
	public void selectOfferableDuplicatesSkipsFoilWhenSecondUnlockedAlreadyOffered()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("f1", "Whip", true, false, 1L),
			inst("f2", "Whip", true, false, 2L));
		Assert.assertTrue(
			MissingDuplicateOfferPlanner.selectOfferableDuplicates(owned, Set.of("f2")).isEmpty());
	}

	@Test
	public void selectOfferableDuplicatesNeverReturnsLockedInstance()
	{
		List<OwnedCardInstance> owned = List.of(
			inst("a1", "Whip", false, true, 1L),
			inst("a2", "Whip", false, false, 2L),
			inst("a3", "Whip", false, false, 3L));

		List<OwnedCardInstance> offerable = MissingDuplicateOfferPlanner.selectOfferableDuplicates(owned, Set.of());
		Assert.assertEquals(1, offerable.size());
		Assert.assertFalse(offerable.get(0).isLocked());
		Assert.assertEquals("a2", offerable.get(0).getInstanceId());
	}

	@Test
	public void variantsMissingFromCollectionIgnoresOwnedAndDuplicates()
	{
		List<CardCollectionKey> queried = List.of(
			new CardCollectionKey("Whip", false),
			new CardCollectionKey("Whip", false),
			new CardCollectionKey("Scim", true),
			new CardCollectionKey("Helm", false));
		Map<CardCollectionKey, Integer> owned = new HashMap<>();
		owned.put(new CardCollectionKey("Whip", false), 2);
		owned.put(new CardCollectionKey("Helm", false), 1);

		List<CardCollectionKey> missing =
			MissingDuplicateOfferPlanner.variantsMissingFromCollection(queried, owned);
		Assert.assertEquals(1, missing.size());
		Assert.assertEquals(new CardCollectionKey("Scim", true), missing.get(0));
	}

	private static OwnedCardInstance inst(String id, String name, boolean foil, boolean locked, long at)
	{
		return new OwnedCardInstance(id, name, foil, "Player", at, locked);
	}
}

package com.osrstcg.service;

import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.TcgState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards against the sell-duplicates race: the plan is computed before a modal confirm dialog,
 * while party trades and gifts keep mutating the collection on the client thread. Applying a
 * stale plan must be rejected instead of clobbering the newer collection.
 */
public class TcgStateServiceSellDuplicatesTest
{
	private static OwnedCardInstance instance(String id, String cardName)
	{
		return new OwnedCardInstance(id, cardName, false, "Player", 1710000000000L);
	}

	private static TcgStateService serviceWith(List<OwnedCardInstance> instances)
	{
		return new TcgStateService(TcgState.empty().withCollection(CollectionState.copyOf(instances)));
	}

	private static Set<String> idsOf(List<OwnedCardInstance> instances)
	{
		Set<String> ids = new HashSet<>();
		for (OwnedCardInstance i : instances)
		{
			ids.add(i.getInstanceId());
		}
		return ids;
	}

	@Test
	public void appliesPlanWhenCollectionUnchanged()
	{
		OwnedCardInstance keeper = instance("a", "Goblin");
		OwnedCardInstance duplicate = instance("b", "Goblin");
		TcgStateService service = serviceWith(List.of(keeper, duplicate));

		boolean applied = service.sellDuplicatesIfUnchanged(
			idsOf(List.of(keeper, duplicate)), List.of(keeper), 50L);

		assertTrue(applied);
		assertEquals(List.of(keeper), service.getState().getCollectionState().getOwnedInstances());
		assertEquals(50L, service.getState().getEconomyState().getCredits());
	}

	@Test
	public void rejectsPlanWhenCardWasAddedMeanwhile()
	{
		OwnedCardInstance keeper = instance("a", "Goblin");
		OwnedCardInstance duplicate = instance("b", "Goblin");
		TcgStateService service = serviceWith(List.of(keeper, duplicate));
		Set<String> plannedIds = idsOf(List.of(keeper, duplicate));

		// A trade or gift lands while the confirm dialog is open.
		service.addOwnedCardInstance(instance("c", "Imp"));

		boolean applied = service.sellDuplicatesIfUnchanged(plannedIds, List.of(keeper), 50L);

		assertFalse(applied);
		assertEquals(3, service.getState().getCollectionState().getOwnedInstances().size());
		assertEquals(0L, service.getState().getEconomyState().getCredits());
	}

	@Test
	public void rejectsPlanWhenCardWasRemovedMeanwhile()
	{
		OwnedCardInstance keeper = instance("a", "Goblin");
		OwnedCardInstance duplicate = instance("b", "Goblin");
		TcgStateService service = serviceWith(List.of(keeper, duplicate));
		Set<String> plannedIds = idsOf(List.of(keeper, duplicate));

		// The duplicate was traded away while the confirm dialog was open; applying the stale
		// plan would resurrect it locally while the trade partner also owns it.
		service.setCollectionInstances(List.of(keeper));

		boolean applied = service.sellDuplicatesIfUnchanged(plannedIds, List.of(keeper), 50L);

		assertFalse(applied);
		assertEquals(List.of(keeper), service.getState().getCollectionState().getOwnedInstances());
		assertEquals(0L, service.getState().getEconomyState().getCredits());
	}

	@Test
	public void rejectsPlanWhenInstanceWasSwapped()
	{
		OwnedCardInstance keeper = instance("a", "Goblin");
		OwnedCardInstance duplicate = instance("b", "Goblin");
		TcgStateService service = serviceWith(List.of(keeper, duplicate));
		Set<String> plannedIds = idsOf(List.of(keeper, duplicate));

		// Same size, different instance: one card traded for another.
		service.setCollectionInstances(List.of(keeper, instance("c", "Imp")));

		boolean applied = service.sellDuplicatesIfUnchanged(plannedIds, List.of(keeper), 50L);

		assertFalse(applied);
	}

	@Test
	public void rejectsNullPlannedIds()
	{
		OwnedCardInstance keeper = instance("a", "Goblin");
		TcgStateService service = serviceWith(List.of(keeper));

		assertFalse(service.sellDuplicatesIfUnchanged(null, List.of(), 0L));
	}
}

package com.osrstcg.cloud.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.state.TcgState;
import com.osrstcg.state.TcgStateService;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class CloudCollectionOpsApplierTest
{
	private TcgStateService stateService;

	@Before
	public void setUp()
	{
		stateService = new TcgStateService(TcgState.empty());
		stateService.addOwnedCardInstances(List.of(
			new OwnedCardInstance("id-a", "Abyssal whip", false, "player", 1L),
			new OwnedCardInstance("id-b", "Dragon scimitar", true, "player", 2L)));
	}

	@Test
	public void nullOpsReturnsFalse()
	{
		assertFalse(CloudCollectionOpsApplier.applyOps(null, stateService));
		assertEquals(2, stateService.getState().getCollectionState().getOwnedInstances().size());
	}

	@Test
	public void emptyOpsReturnsFalse()
	{
		assertFalse(CloudCollectionOpsApplier.applyOps(new JsonArray(), stateService));
	}

	@Test
	public void soldRemovesInstanceById()
	{
		JsonArray ops = new JsonArray();
		ops.add(op("sold", "id-a", null));
		assertTrue(CloudCollectionOpsApplier.applyOps(ops, stateService));
		List<OwnedCardInstance> left = stateService.getState().getCollectionState().getOwnedInstances();
		assertEquals(1, left.size());
		assertEquals("id-b", left.get(0).getInstanceId());
	}

	@Test
	public void removeWithoutIdFailsClosed()
	{
		JsonArray ops = new JsonArray();
		ops.add(op("remove", null, null));
		assertFalse(CloudCollectionOpsApplier.applyOps(ops, stateService));
		assertEquals(2, stateService.getState().getCollectionState().getOwnedInstances().size());
	}

	@Test
	public void addWithoutCardFailsClosed()
	{
		JsonArray ops = new JsonArray();
		ops.add(op("add", "id-c", null));
		assertFalse(CloudCollectionOpsApplier.applyOps(ops, stateService));
		assertEquals(2, stateService.getState().getCollectionState().getOwnedInstances().size());
	}

	@Test
	public void unknownOpFailsClosed()
	{
		JsonArray ops = new JsonArray();
		ops.add(op("explode", "id-a", null));
		assertFalse(CloudCollectionOpsApplier.applyOps(ops, stateService));
	}

	@Test
	public void addUsesOpInstanceIdWhenCardIdMissing()
	{
		JsonObject card = new JsonObject();
		card.addProperty("cardName", "Rune platebody");
		card.addProperty("foil", false);
		JsonArray ops = new JsonArray();
		ops.add(op("add", "id-c", card));
		assertTrue(CloudCollectionOpsApplier.applyOps(ops, stateService));
		List<OwnedCardInstance> owned = stateService.getState().getCollectionState().getOwnedInstances();
		assertEquals(3, owned.size());
		assertTrue(owned.stream().anyMatch(c -> "id-c".equals(c.getInstanceId())));
	}

	@Test
	public void addWithoutAnyInstanceIdFailsClosed()
	{
		JsonObject card = new JsonObject();
		card.addProperty("cardName", "Rune platebody");
		JsonArray ops = new JsonArray();
		ops.add(op("add", null, card));
		assertFalse(CloudCollectionOpsApplier.applyOps(ops, stateService));
		assertEquals(2, stateService.getState().getCollectionState().getOwnedInstances().size());
	}

	@Test
	public void lockUnlockAloneSucceedsWithoutMutating()
	{
		JsonArray ops = new JsonArray();
		ops.add(op("lock", "id-a", null));
		ops.add(op("unlock", "id-a", null));
		assertTrue(CloudCollectionOpsApplier.applyOps(ops, stateService));
		assertEquals(2, stateService.getState().getCollectionState().getOwnedInstances().size());
	}

	@Test
	public void allNonObjectElementsFailClosed()
	{
		JsonArray ops = new JsonArray();
		ops.add(com.google.gson.JsonNull.INSTANCE);
		ops.add(new com.google.gson.JsonPrimitive(1));
		ops.add(new JsonArray());
		assertFalse(CloudCollectionOpsApplier.applyOps(ops, stateService));
	}

	@Test
	public void lockThenSoldCountsAsMembership()
	{
		JsonArray ops = new JsonArray();
		ops.add(op("lock", "id-a", null));
		ops.add(op("sold", "id-a", null));
		assertTrue(CloudCollectionOpsApplier.applyOps(ops, stateService));
		assertEquals(1, stateService.getState().getCollectionState().getOwnedInstances().size());
	}

	private static JsonObject op(String kind, String instanceId, JsonObject card)
	{
		JsonObject o = new JsonObject();
		o.addProperty("op", kind);
		if (instanceId != null)
		{
			o.addProperty("instanceId", instanceId);
		}
		if (card != null)
		{
			o.add("card", card);
		}
		return o;
	}
}

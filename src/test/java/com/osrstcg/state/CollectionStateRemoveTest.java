package com.osrstcg.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class CollectionStateRemoveTest
{
	@Test
	public void withInstancesRemovedDropsMatchingIds()
	{
		CollectionState state = CollectionState.copyOf(List.of(
			new OwnedCardInstance("a", "A", false, "", 0L),
			new OwnedCardInstance("b", "B", false, "", 0L),
			new OwnedCardInstance("c", "C", true, "", 0L)));
		CollectionState next = state.withInstancesRemoved(Set.of("b", "missing"));
		assertEquals(2, next.getOwnedInstances().size());
		assertEquals("a", next.getOwnedInstances().get(0).getInstanceId());
		assertEquals("c", next.getOwnedInstances().get(1).getInstanceId());
	}

	@Test
	public void withInstancesRemovedNoOpWhenNoneMatch()
	{
		CollectionState state = CollectionState.copyOf(List.of(
			new OwnedCardInstance("a", "A", false, "", 0L)));
		assertSame(state, state.withInstancesRemoved(Set.of("z")));
		assertSame(state, state.withInstancesRemoved(Set.of()));
		assertSame(state, state.withInstancesRemoved(null));
	}

	@Test
	public void withoutTempsDropsTempsOnly()
	{
		CollectionState state = CollectionState.copyOf(List.of(
			new OwnedCardInstance("a", "A", false, "", 0L, false),
			new OwnedCardInstance("b", "B", false, "", 0L, true),
			new OwnedCardInstance("c", "C", true, "", 0L, true)));
		CollectionState next = state.withoutTemps();
		assertEquals(1, next.getOwnedInstances().size());
		assertEquals("a", next.getOwnedInstances().get(0).getInstanceId());
		assertSame(next, next.withoutTemps());
	}
}

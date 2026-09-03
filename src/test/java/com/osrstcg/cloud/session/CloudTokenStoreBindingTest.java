package com.osrstcg.cloud.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CloudTokenStoreBindingTest
{
	@Test
	public void isBoundToRequiresExactMatch()
	{
		assertTrue(CloudTokenStore.isBoundTo(42L, 42L));
		assertFalse(CloudTokenStore.isBoundTo(42L, 99L));
		assertFalse(CloudTokenStore.isBoundTo(-1L, 42L));
		assertFalse(CloudTokenStore.isBoundTo(42L, -1L));
	}

	@Test
	public void parseBoundAccountHashHandlesMissingAndInvalid()
	{
		assertEquals(12345L, CloudTokenStore.parseBoundAccountHash("12345"));
		assertEquals(-1L, CloudTokenStore.parseBoundAccountHash(null));
		assertEquals(-1L, CloudTokenStore.parseBoundAccountHash(""));
		assertEquals(-1L, CloudTokenStore.parseBoundAccountHash("  "));
		assertEquals(-1L, CloudTokenStore.parseBoundAccountHash("not-a-number"));
	}

	@Test
	public void shouldClearWhenRefreshExistsButNotBoundToLiveAccount()
	{
		assertTrue(CloudTokenStore.shouldClearForAccount(1L, true, 2L));
		assertTrue(CloudTokenStore.shouldClearForAccount(-1L, true, 2L));
		assertFalse(CloudTokenStore.shouldClearForAccount(2L, true, 2L));
		assertFalse(CloudTokenStore.shouldClearForAccount(1L, false, 2L));
		assertFalse(CloudTokenStore.shouldClearForAccount(1L, true, -1L));
	}
}

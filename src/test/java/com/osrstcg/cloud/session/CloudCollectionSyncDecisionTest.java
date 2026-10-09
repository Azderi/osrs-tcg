package com.osrstcg.cloud.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CloudCollectionSyncDecisionTest
{
	@Test
	public void equalRevisionsSkipSyncEvenWithEmptyLocalCollectionHash()
	{
		assertFalse(CloudCollectionSyncService.needsCollectionSync(5L, "", 5L, "abc"));
		assertFalse(CloudCollectionSyncService.needsCollectionSync(5L, null, 5L, "abc"));
		assertFalse(CloudCollectionSyncService.needsCollectionSync(0L, "", 0L, "abc"));
	}

	@Test
	public void behindRevisionWithDifferingCollectionHashNeedsSync()
	{
		assertTrue(CloudCollectionSyncService.needsCollectionSync(4L, "", 5L, "abc"));
		assertTrue(CloudCollectionSyncService.needsCollectionSync(4L, "old", 5L, "abc"));
	}

	@Test
	public void behindRevisionWithMatchingCollectionHashSkipsSync()
	{
		assertFalse(CloudCollectionSyncService.needsCollectionSync(4L, "abc", 5L, "abc"));
		assertFalse(CloudCollectionSyncService.needsCollectionSync(4L, "ABC", 5L, "abc"));
	}

	@Test
	public void legacyEmptyServerHashUsesRevisionBehind()
	{
		assertTrue(CloudCollectionSyncService.needsCollectionSync(4L, "", 5L, ""));
		assertTrue(CloudCollectionSyncService.needsCollectionSync(4L, "anything", 5L, null));
		assertFalse(CloudCollectionSyncService.needsCollectionSync(5L, "", 5L, ""));
		assertFalse(CloudCollectionSyncService.needsCollectionSync(6L, "", 5L, ""));
	}

	@Test
	public void changesRevisionBehindLocalIsUnacceptable()
	{
		// Used before ops apply and again immediately before marker write (TOCTOU).
		assertFalse(CloudCollectionSyncService.isChangesRevisionAcceptable(10L, 9L));
		assertTrue(CloudCollectionSyncService.isChangesRevisionAcceptable(10L, 10L));
		assertTrue(CloudCollectionSyncService.isChangesRevisionAcceptable(10L, 11L));
	}
}

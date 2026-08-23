package com.osrstcg.ui.layout;

import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.CollectionState;
import java.util.Collections;
import java.util.Map;

/** Frozen collection/economy snapshot used while a pack reveal is open, and for tab rebuilds. */
public final class PackCloseSnapshot
{
	public final Map<CardCollectionKey, Integer> owned;
	/** Frozen collection used by the Collection tab (includes pulled-at; beta still excluded by model). */
	public final CollectionState collectionState;
	public final long credits;
	public final long openedPacks;
	/** Cloud or local overview captured with this snapshot (null → compute locally). */
	public final CloudSidebarCollectionStats collectionStats;

	public PackCloseSnapshot(
		Map<CardCollectionKey, Integer> owned,
		CollectionState collectionState,
		long credits,
		long openedPacks,
		CloudSidebarCollectionStats collectionStats)
	{
		this.owned = owned == null ? Map.of() : Collections.unmodifiableMap(owned);
		this.collectionState = collectionState == null ? CollectionState.empty() : collectionState;
		this.credits = credits;
		this.openedPacks = openedPacks;
		this.collectionStats = collectionStats;
	}
}

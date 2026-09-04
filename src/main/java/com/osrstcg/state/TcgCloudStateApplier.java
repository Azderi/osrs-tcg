package com.osrstcg.state;
/** Pure cloud→local state apply used by {@link TcgStateService} under its lock. */
final class TcgCloudStateApplier
{
/** Static-only helper; not instantiable. */
	private TcgCloudStateApplier()
	{
	}
/** Applies economy-only fields from a cloud response (credits, opened packs, total gained), clamping to valid ranges. */
	static TcgState applyEconomy(TcgState state, long credits, int openedPacks, long totalCreditsGained)
	{
		return state
			.withCredits(Math.max(0L, credits))
			.withOpenedPacks(openedPacks)
			.withTotalCreditsGained(Math.max(totalCreditsGained, credits));
	}
/** Applies a full cloud state sync (collection, economy, sync markers), substituting empty defaults for nulls. */
	static TcgState applyFull(
		TcgState state,
		CollectionState collection,
		EconomyState economy,
		long totalCreditsGained,
		long cloudRevision,
		String cloudStateHash)
	{
		CollectionState nextCollection = collection == null ? CollectionState.empty() : collection;
		EconomyState nextEconomy = economy == null ? EconomyState.empty() : economy;
		return state
			.withCollection(nextCollection)
			.withEconomy(nextEconomy, Math.max(0L, totalCreditsGained))
			.withCloudSyncMarkers(cloudRevision, cloudStateHash);
	}
}

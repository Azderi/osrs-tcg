package com.osrstcg.state;

import com.osrstcg.util.PackRevealZoomUtil;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * Immutable root of persisted plugin state: economy, collection, cloud sync markers, and profile
 * metadata. Persisted to disk/cloud via {@code TcgStateStore}; every mutation is expressed as a
 * {@code withXxx} method returning a new instance.
 */
@Getter
public final class TcgState
{
	public static final int CURRENT_SCHEMA_VERSION = 6;
	public static final int SIDEBAR_RANK_COUNT = 6;

	private final int schemaVersion;
	private final EconomyState economyState;
	private final CollectionState collectionState;
	private final double packRevealOverlayScale;
	private final SkillCreditBaseline skillCreditBaseline;
	private final long totalCreditsGained;
	private final long profileCreatedAtUnix;
	private final long profileSavedAtUnix;
	private final long cloudRevision;
	private final String cloudStateHash;
	@Getter(AccessLevel.NONE)
	private final int[] sidebarRanks;

	/** Legacy-schema convenience overload: no cloud sync markers or sidebar ranks. */
	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix)
	{
		this(schemaVersion, economyState, collectionState, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, 0L, "", null);
	}

	/** Convenience overload: no sidebar ranks. */
	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix,
		long cloudRevision, String cloudStateHash)
	{
		this(schemaVersion, economyState, collectionState, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, null);
	}

	/**
	 * Full constructor. Normalizes every field: a non-positive schema version falls back to
	 * {@link #CURRENT_SCHEMA_VERSION}, null sub-states become their {@code empty()}/{@code absent()}
	 * forms, the overlay scale is clamped via {@link PackRevealZoomUtil}, and numeric fields are
	 * floored at zero.
	 */
	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix,
		long cloudRevision, String cloudStateHash, int[] sidebarRanks)
	{
		this.schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
		this.economyState = economyState == null ? EconomyState.empty() : economyState;
		this.collectionState = collectionState == null ? CollectionState.empty() : collectionState;
		this.packRevealOverlayScale = PackRevealZoomUtil.clamp(packRevealOverlayScale);
		this.skillCreditBaseline = skillCreditBaseline == null ? SkillCreditBaseline.absent() : skillCreditBaseline;
		this.totalCreditsGained = Math.max(0L, totalCreditsGained);
		this.profileCreatedAtUnix = Math.max(0L, profileCreatedAtUnix);
		this.profileSavedAtUnix = Math.max(0L, profileSavedAtUnix);
		this.cloudRevision = Math.max(0L, cloudRevision);
		this.cloudStateHash = cloudStateHash == null ? "" : cloudStateHash.trim();
		this.sidebarRanks = copyRanks(sidebarRanks);
	}

	/** Returns a fresh state for a new profile: zero economy, no cards, {@code profileCreatedAtUnix} set to now. */
	public static TcgState empty()
	{
		long now = currentUnixSeconds();
		return new TcgState(CURRENT_SCHEMA_VERSION, EconomyState.empty(), CollectionState.empty(),
			1.0d, SkillCreditBaseline.absent(),
			0L, now, 0L, 0L, "", null);
	}

	/** Returns the current time as Unix seconds, used to stamp {@code profileCreatedAtUnix}/{@code profileSavedAtUnix}. */
	public static long currentUnixSeconds()
	{
		return System.currentTimeMillis() / 1000L;
	}

	/**
	 * Validates and defensively copies a sidebar-rank array: returns null if the length doesn't
	 * match {@link #SIDEBAR_RANK_COUNT} or any entry is negative.
	 */
	public static int[] copyRanks(int[] ranks)
	{
		if (ranks == null || ranks.length != SIDEBAR_RANK_COUNT)
		{
			return null;
		}
		for (int rank : ranks)
		{
			if (rank < 0)
			{
				return null;
			}
		}
		return Arrays.copyOf(ranks, SIDEBAR_RANK_COUNT);
	}

	/** Returns a defensive copy of the sidebar ranks array, or null if unset. */
	public int[] getSidebarRanks()
	{
		return sidebarRanks == null ? null : Arrays.copyOf(sidebarRanks, sidebarRanks.length);
	}

	/** Builds a new {@code TcgState} with the given fields, keeping this instance's {@link #schemaVersion}. */
	private TcgState copy(
		EconomyState economy,
		CollectionState collection,
		double packZoom,
		SkillCreditBaseline baseline,
		long gained,
		long createdAt,
		long savedAt,
		long revision,
		String stateHash,
		int[] ranks)
	{
		return new TcgState(schemaVersion, economy, collection, packZoom,
			baseline, gained, createdAt, savedAt, revision, stateHash, ranks);
	}

	/** Returns a copy with the credit balance replaced, opened-pack count unchanged. */
	public TcgState withCredits(long newCredits)
	{
		return copy(new EconomyState(newCredits, economyState.getOpenedPacks()), collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the opened-pack count replaced, credit balance unchanged. */
	public TcgState withOpenedPacks(long openedPacks)
	{
		return copy(new EconomyState(economyState.getCredits(), openedPacks), collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the collection state replaced. */
	public TcgState withCollection(CollectionState newCollectionState)
	{
		return copy(economyState, newCollectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the pack reveal overlay scale replaced (clamped by the constructor). */
	public TcgState withPackRevealOverlayScale(double multiplier)
	{
		return copy(economyState, collectionState, multiplier, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the skill credit baseline replaced; null falls back to {@code absent()}. */
	public TcgState withSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, baseline == null ? SkillCreditBaseline.absent() : baseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the lifetime total credits gained replaced. */
	public TcgState withTotalCreditsGained(long gained)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, gained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the profile creation timestamp replaced. */
	public TcgState withProfileCreatedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, unixSeconds, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the profile last-saved timestamp replaced. */
	public TcgState withProfileSavedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, unixSeconds, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the cloud revision number and state hash replaced. */
	public TcgState withCloudSyncMarkers(long revision, String stateHash)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, revision, stateHash, sidebarRanks);
	}

	/** Returns a copy with the economy state and total credits gained replaced together; null economy falls back to {@code empty()}. */
	public TcgState withEconomy(EconomyState nextEconomy, long totalGained)
	{
		return copy(nextEconomy == null ? EconomyState.empty() : nextEconomy, collectionState, packRevealOverlayScale, skillCreditBaseline, totalGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	/** Returns a copy with the sidebar ranks replaced (validated by {@link #copyRanks}). */
	public TcgState withSidebarRanks(int[] ranks)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, ranks);
	}
}

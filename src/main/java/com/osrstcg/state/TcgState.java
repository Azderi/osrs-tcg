package com.osrstcg.state;

import com.osrstcg.util.PackRevealZoomUtil;
import java.util.Arrays;

public final class TcgState
{
	public static final int CURRENT_SCHEMA_VERSION = 6;
	public static final int SIDEBAR_RANK_COUNT = 6;

	private final int schemaVersion;
	private final EconomyState economyState;
	private final CollectionState collectionState;
	private final boolean debugLogging;
	private final double packRevealOverlayScale;
	private final SkillCreditBaseline skillCreditBaseline;
	/** Lifetime credits awarded (not reduced by spending). */
	private final long totalCreditsGained;
	/** Unix epoch seconds when this profile was first created; 0 if unknown/legacy. */
	private final long profileCreatedAtUnix;
	/** Unix epoch seconds of the most recent successful persist; 0 if never saved. */
	private final long profileSavedAtUnix;
	/** Last applied cloud {@code economy.revision}; 0 if never synced. */
	private final long cloudRevision;
	/** Last applied cloud {@code economy.stateHash}; empty if never synced. */
	private final String cloudStateHash;
	/**
	 * Last hiscores ranks from pack-open (length {@link #SIDEBAR_RANK_COUNT}), or null.
	 * Order: completionPct, foilCompletionPct, openedPacks, collectionScore, totalCardsOwned, foilOwned.
	 * {@code 0} in a slot means unranked (hidden in the sidebar).
	 */
	private final int[] sidebarRanks;

	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		boolean debugLogging, double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix)
	{
		this(schemaVersion, economyState, collectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, 0L, "", null);
	}

	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		boolean debugLogging, double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix,
		long cloudRevision, String cloudStateHash)
	{
		this(schemaVersion, economyState, collectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, null);
	}

	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		boolean debugLogging, double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix,
		long cloudRevision, String cloudStateHash, int[] sidebarRanks)
	{
		this.schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
		this.economyState = economyState == null ? EconomyState.empty() : economyState;
		this.collectionState = collectionState == null ? CollectionState.empty() : collectionState;
		this.debugLogging = debugLogging;
		this.packRevealOverlayScale = PackRevealZoomUtil.clamp(packRevealOverlayScale);
		this.skillCreditBaseline = skillCreditBaseline == null ? SkillCreditBaseline.absent() : skillCreditBaseline;
		this.totalCreditsGained = Math.max(0L, totalCreditsGained);
		this.profileCreatedAtUnix = Math.max(0L, profileCreatedAtUnix);
		this.profileSavedAtUnix = Math.max(0L, profileSavedAtUnix);
		this.cloudRevision = Math.max(0L, cloudRevision);
		this.cloudStateHash = cloudStateHash == null ? "" : cloudStateHash.trim();
		this.sidebarRanks = copyRanks(sidebarRanks);
	}

	public static TcgState empty()
	{
		long now = currentUnixSeconds();
		return new TcgState(CURRENT_SCHEMA_VERSION, EconomyState.empty(), CollectionState.empty(),
			false, 1.0d, SkillCreditBaseline.absent(),
			0L, now, 0L, 0L, "", null);
	}

	public static long currentUnixSeconds()
	{
		return System.currentTimeMillis() / 1000L;
	}

	/**
	 * @return defensive copy of length-{@link #SIDEBAR_RANK_COUNT} ranks, or null.
	 * {@code 0} means unranked for that slot; negative values reject the whole array.
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

	public int getSchemaVersion()
	{
		return schemaVersion;
	}

	public EconomyState getEconomyState()
	{
		return economyState;
	}

	public CollectionState getCollectionState()
	{
		return collectionState;
	}

	public boolean isDebugLogging()
	{
		return debugLogging;
	}

	public double getPackRevealOverlayScale()
	{
		return packRevealOverlayScale;
	}

	public SkillCreditBaseline getSkillCreditBaseline()
	{
		return skillCreditBaseline;
	}

	public long getTotalCreditsGained()
	{
		return totalCreditsGained;
	}

	public long getProfileCreatedAtUnix()
	{
		return profileCreatedAtUnix;
	}

	public long getProfileSavedAtUnix()
	{
		return profileSavedAtUnix;
	}

	public long getCloudRevision()
	{
		return cloudRevision;
	}

	public String getCloudStateHash()
	{
		return cloudStateHash;
	}

	/** @return defensive copy of cached hiscores ranks, or null */
	public int[] getSidebarRanks()
	{
		return sidebarRanks == null ? null : Arrays.copyOf(sidebarRanks, sidebarRanks.length);
	}

	private TcgState copy(
		EconomyState economy,
		CollectionState collection,
		boolean debug,
		double packZoom,
		SkillCreditBaseline baseline,
		long gained,
		long createdAt,
		long savedAt,
		long revision,
		String stateHash,
		int[] ranks)
	{
		return new TcgState(schemaVersion, economy, collection, debug, packZoom,
			baseline, gained, createdAt, savedAt, revision, stateHash, ranks);
	}

	public TcgState withCredits(long newCredits)
	{
		return copy(new EconomyState(newCredits, economyState.getOpenedPacks()), collectionState,
			debugLogging, packRevealOverlayScale, skillCreditBaseline,
			totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash,
			sidebarRanks);
	}

	public TcgState withOpenedPacks(long openedPacks)
	{
		return copy(new EconomyState(economyState.getCredits(), openedPacks), collectionState,
			debugLogging, packRevealOverlayScale, skillCreditBaseline,
			totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash,
			sidebarRanks);
	}

	public TcgState withCollection(CollectionState newCollectionState)
	{
		return copy(economyState, newCollectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withDebugLogging(boolean enabled)
	{
		return copy(economyState, collectionState, enabled, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withPackRevealOverlayScale(double multiplier)
	{
		return copy(economyState, collectionState, debugLogging, multiplier,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		return copy(economyState, collectionState, debugLogging, packRevealOverlayScale,
			baseline == null ? SkillCreditBaseline.absent() : baseline,
			totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash,
			sidebarRanks);
	}

	public TcgState withTotalCreditsGained(long gained)
	{
		return copy(economyState, collectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, gained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withProfileCreatedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, unixSeconds,
			profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withProfileSavedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			unixSeconds, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withCloudSyncMarkers(long revision, String stateHash)
	{
		return copy(economyState, collectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, revision, stateHash, sidebarRanks);
	}

	public TcgState withEconomy(EconomyState nextEconomy, long totalGained)
	{
		return copy(nextEconomy == null ? EconomyState.empty() : nextEconomy, collectionState,
			debugLogging, packRevealOverlayScale, skillCreditBaseline,
			totalGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withSidebarRanks(int[] ranks)
	{
		return copy(economyState, collectionState, debugLogging, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, ranks);
	}
}

package com.osrstcg.state;

import com.osrstcg.util.PackRevealZoomUtil;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;

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

	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix)
	{
		this(schemaVersion, economyState, collectionState, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, 0L, "", null);
	}

	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		double packRevealOverlayScale, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix,
		long cloudRevision, String cloudStateHash)
	{
		this(schemaVersion, economyState, collectionState, packRevealOverlayScale,
			skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix, cloudRevision, cloudStateHash, null);
	}

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

	public static TcgState empty()
	{
		long now = currentUnixSeconds();
		return new TcgState(CURRENT_SCHEMA_VERSION, EconomyState.empty(), CollectionState.empty(),
			1.0d, SkillCreditBaseline.absent(),
			0L, now, 0L, 0L, "", null);
	}

	public static long currentUnixSeconds()
	{
		return System.currentTimeMillis() / 1000L;
	}

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

	public int[] getSidebarRanks()
	{
		return sidebarRanks == null ? null : Arrays.copyOf(sidebarRanks, sidebarRanks.length);
	}

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

	public TcgState withCredits(long newCredits)
	{
		return copy(new EconomyState(newCredits, economyState.getOpenedPacks()), collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withOpenedPacks(long openedPacks)
	{
		return copy(new EconomyState(economyState.getCredits(), openedPacks), collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withCollection(CollectionState newCollectionState)
	{
		return copy(economyState, newCollectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withPackRevealOverlayScale(double multiplier)
	{
		return copy(economyState, collectionState, multiplier, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, baseline == null ? SkillCreditBaseline.absent() : baseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withTotalCreditsGained(long gained)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, gained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withProfileCreatedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, unixSeconds, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withProfileSavedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, unixSeconds, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withCloudSyncMarkers(long revision, String stateHash)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, revision, stateHash, sidebarRanks);
	}

	public TcgState withEconomy(EconomyState nextEconomy, long totalGained)
	{
		return copy(nextEconomy == null ? EconomyState.empty() : nextEconomy, collectionState, packRevealOverlayScale, skillCreditBaseline, totalGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, sidebarRanks);
	}

	public TcgState withSidebarRanks(int[] ranks)
	{
		return copy(economyState, collectionState, packRevealOverlayScale, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix, cloudRevision, cloudStateHash, ranks);
	}
}

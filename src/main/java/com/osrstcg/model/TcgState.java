package com.osrstcg.model;

import com.osrstcg.util.PackRevealZoomUtil;

public final class TcgState
{
	public static final int CURRENT_SCHEMA_VERSION = 5;

	private final int schemaVersion;
	private final EconomyState economyState;
	private final CollectionState collectionState;
	private final RewardTuningState rewardTuning;
	private final boolean debugLogging;
	private final double packRevealOverlayScale;
	private final int albumWindowWidth;
	private final int albumWindowHeight;
	private final SkillCreditBaseline skillCreditBaseline;
	/** Lifetime credits awarded (not reduced by spending). */
	private final long totalCreditsGained;
	/** Unix epoch seconds when this profile was first created; 0 if unknown/legacy. */
	private final long profileCreatedAtUnix;
	/** Unix epoch seconds of the most recent successful persist; 0 if never saved. */
	private final long profileSavedAtUnix;

	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		RewardTuningState rewardTuning, boolean debugLogging, double packRevealOverlayScale,
		int albumWindowWidth, int albumWindowHeight, SkillCreditBaseline skillCreditBaseline,
		long totalCreditsGained, long profileCreatedAtUnix, long profileSavedAtUnix)
	{
		this.schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
		this.economyState = economyState == null ? EconomyState.empty() : economyState;
		this.collectionState = collectionState == null ? CollectionState.empty() : collectionState;
		this.rewardTuning = rewardTuning == null ? RewardTuningState.DEFAULTS : rewardTuning;
		this.debugLogging = debugLogging;
		this.packRevealOverlayScale = PackRevealZoomUtil.clamp(packRevealOverlayScale);
		this.albumWindowWidth = Math.max(0, albumWindowWidth);
		this.albumWindowHeight = Math.max(0, albumWindowHeight);
		this.skillCreditBaseline = skillCreditBaseline == null ? SkillCreditBaseline.absent() : skillCreditBaseline;
		this.totalCreditsGained = Math.max(0L, totalCreditsGained);
		this.profileCreatedAtUnix = Math.max(0L, profileCreatedAtUnix);
		this.profileSavedAtUnix = Math.max(0L, profileSavedAtUnix);
	}

	public static TcgState empty()
	{
		long now = currentUnixSeconds();
		return new TcgState(CURRENT_SCHEMA_VERSION, EconomyState.empty(), CollectionState.empty(),
			RewardTuningState.DEFAULTS, false, 1.0d, 0, 0, SkillCreditBaseline.absent(),
			0L, now, 0L);
	}

	public static long currentUnixSeconds()
	{
		return System.currentTimeMillis() / 1000L;
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

	public RewardTuningState getRewardTuning()
	{
		return rewardTuning;
	}

	public boolean isDebugLogging()
	{
		return debugLogging;
	}

	public double getPackRevealOverlayScale()
	{
		return packRevealOverlayScale;
	}

	public int getAlbumWindowWidth()
	{
		return albumWindowWidth;
	}

	public int getAlbumWindowHeight()
	{
		return albumWindowHeight;
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

	private TcgState copy(
		EconomyState economy,
		CollectionState collection,
		RewardTuningState tuning,
		boolean debug,
		double packZoom,
		int albumW,
		int albumH,
		SkillCreditBaseline baseline,
		long gained,
		long createdAt,
		long savedAt)
	{
		return new TcgState(schemaVersion, economy, collection, tuning, debug, packZoom, albumW, albumH,
			baseline, gained, createdAt, savedAt);
	}

	public TcgState withCredits(long newCredits)
	{
		return copy(new EconomyState(newCredits, economyState.getOpenedPacks()), collectionState, rewardTuning,
			debugLogging, packRevealOverlayScale, albumWindowWidth, albumWindowHeight, skillCreditBaseline,
			totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix);
	}

	public TcgState withOpenedPacks(long openedPacks)
	{
		return copy(new EconomyState(economyState.getCredits(), openedPacks), collectionState, rewardTuning,
			debugLogging, packRevealOverlayScale, albumWindowWidth, albumWindowHeight, skillCreditBaseline,
			totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix);
	}

	public TcgState withCollection(CollectionState newCollectionState)
	{
		return copy(economyState, newCollectionState, rewardTuning, debugLogging, packRevealOverlayScale,
			albumWindowWidth, albumWindowHeight, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix);
	}

	public TcgState withRewardTuning(RewardTuningState next)
	{
		return copy(economyState, collectionState, next == null ? RewardTuningState.DEFAULTS : next,
			debugLogging, packRevealOverlayScale, albumWindowWidth, albumWindowHeight, skillCreditBaseline,
			totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix);
	}

	public TcgState withDebugLogging(boolean enabled)
	{
		return copy(economyState, collectionState, rewardTuning, enabled, packRevealOverlayScale,
			albumWindowWidth, albumWindowHeight, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix);
	}

	public TcgState withPackRevealOverlayScale(double multiplier)
	{
		return copy(economyState, collectionState, rewardTuning, debugLogging, multiplier,
			albumWindowWidth, albumWindowHeight, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			profileSavedAtUnix);
	}

	public TcgState withAlbumWindowSize(int width, int height)
	{
		return copy(economyState, collectionState, rewardTuning, debugLogging, packRevealOverlayScale,
			width, height, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix);
	}

	public TcgState withSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		return copy(economyState, collectionState, rewardTuning, debugLogging, packRevealOverlayScale,
			albumWindowWidth, albumWindowHeight,
			baseline == null ? SkillCreditBaseline.absent() : baseline,
			totalCreditsGained, profileCreatedAtUnix, profileSavedAtUnix);
	}

	public TcgState withTotalCreditsGained(long gained)
	{
		return copy(economyState, collectionState, rewardTuning, debugLogging, packRevealOverlayScale,
			albumWindowWidth, albumWindowHeight, skillCreditBaseline, gained, profileCreatedAtUnix,
			profileSavedAtUnix);
	}

	public TcgState withProfileCreatedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, rewardTuning, debugLogging, packRevealOverlayScale,
			albumWindowWidth, albumWindowHeight, skillCreditBaseline, totalCreditsGained, unixSeconds,
			profileSavedAtUnix);
	}

	public TcgState withProfileSavedAtUnix(long unixSeconds)
	{
		return copy(economyState, collectionState, rewardTuning, debugLogging, packRevealOverlayScale,
			albumWindowWidth, albumWindowHeight, skillCreditBaseline, totalCreditsGained, profileCreatedAtUnix,
			unixSeconds);
	}
}

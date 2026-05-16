package com.osrstcg.model;

import com.osrstcg.util.PackRevealZoomUtil;

public final class TcgState
{
	public static final int CURRENT_SCHEMA_VERSION = 3;

	private final int schemaVersion;
	private final EconomyState economyState;
	private final CollectionState collectionState;
	private final RewardTuningState rewardTuning;
	private final boolean debugLogging;
	private final double packRevealOverlayScale;
	private final int albumWindowWidth;
	private final int albumWindowHeight;

	public TcgState(int schemaVersion, EconomyState economyState, CollectionState collectionState,
		RewardTuningState rewardTuning, boolean debugLogging, double packRevealOverlayScale,
		int albumWindowWidth, int albumWindowHeight)
	{
		this.schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
		this.economyState = economyState == null ? EconomyState.empty() : economyState;
		this.collectionState = collectionState == null ? CollectionState.empty() : collectionState;
		this.rewardTuning = rewardTuning == null ? RewardTuningState.DEFAULTS : rewardTuning;
		this.debugLogging = debugLogging;
		this.packRevealOverlayScale = PackRevealZoomUtil.clamp(packRevealOverlayScale);
		this.albumWindowWidth = Math.max(0, albumWindowWidth);
		this.albumWindowHeight = Math.max(0, albumWindowHeight);
	}

	public static TcgState empty()
	{
		return new TcgState(CURRENT_SCHEMA_VERSION, EconomyState.empty(), CollectionState.empty(),
			RewardTuningState.DEFAULTS, false, 1.0d, 0, 0);
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

	public TcgState withCredits(long newCredits)
	{
		return new TcgState(schemaVersion, new EconomyState(newCredits, economyState.getOpenedPacks()),
			collectionState, rewardTuning, debugLogging, packRevealOverlayScale, albumWindowWidth, albumWindowHeight);
	}

	public TcgState withOpenedPacks(long openedPacks)
	{
		return new TcgState(schemaVersion, new EconomyState(economyState.getCredits(), openedPacks),
			collectionState, rewardTuning, debugLogging, packRevealOverlayScale, albumWindowWidth, albumWindowHeight);
	}

	public TcgState withCollection(CollectionState newCollectionState)
	{
		return new TcgState(schemaVersion, economyState, newCollectionState, rewardTuning,
			debugLogging, packRevealOverlayScale, albumWindowWidth, albumWindowHeight);
	}

	public TcgState withRewardTuning(RewardTuningState next)
	{
		return new TcgState(schemaVersion, economyState, collectionState,
			next == null ? RewardTuningState.DEFAULTS : next, debugLogging, packRevealOverlayScale,
			albumWindowWidth, albumWindowHeight);
	}

	public TcgState withDebugLogging(boolean enabled)
	{
		return new TcgState(schemaVersion, economyState, collectionState, rewardTuning, enabled,
			packRevealOverlayScale, albumWindowWidth, albumWindowHeight);
	}

	public TcgState withPackRevealOverlayScale(double multiplier)
	{
		return new TcgState(schemaVersion, economyState, collectionState, rewardTuning, debugLogging,
			multiplier, albumWindowWidth, albumWindowHeight);
	}

	public TcgState withAlbumWindowSize(int width, int height)
	{
		return new TcgState(schemaVersion, economyState, collectionState, rewardTuning, debugLogging,
			packRevealOverlayScale, width, height);
	}
}

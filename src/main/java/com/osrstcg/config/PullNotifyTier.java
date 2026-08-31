package com.osrstcg.config;

import com.osrstcg.catalog.RarityMath;

/** Minimum display tier for pack-pull chat notifications (this tier and higher). */
public enum PullNotifyTier
{
	COMMON(RarityMath.Tier.COMMON),
	UNCOMMON(RarityMath.Tier.UNCOMMON),
	RARE(RarityMath.Tier.RARE),
	EPIC(RarityMath.Tier.EPIC),
	LEGENDARY(RarityMath.Tier.LEGENDARY),
	MYTHIC(RarityMath.Tier.MYTHIC),
	GODLY(RarityMath.Tier.GODLY);

	private final RarityMath.Tier tier;

	PullNotifyTier(RarityMath.Tier tier)
	{
		this.tier = tier;
	}

	public RarityMath.Tier toRarityTier()
	{
		return tier;
	}

	public String displayLabel()
	{
		return tier.getLabel();
	}

	@Override
	public String toString()
	{
		return displayLabel();
	}
}

package com.osrstcg.config;

import com.osrstcg.catalog.RarityMath;
/** Config option mirror of {@link RarityMath.Tier}, used as the "notify at or above this rarity" threshold setting. */
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
/** @param tier corresponding {@link RarityMath.Tier}. */
	PullNotifyTier(RarityMath.Tier tier)
	{
		this.tier = tier;
	}
/** @return the corresponding {@link RarityMath.Tier}. */
	public RarityMath.Tier toRarityTier()
	{
		return tier;
	}
/** @return true if {@code value} is this tier or rarer (higher ordinal). */
	public boolean meetsOrExceeds(RarityMath.Tier value)
	{
		return value != null && value.ordinal() >= tier.ordinal();
	}
/** @return display label from the underlying {@link RarityMath.Tier}. */
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

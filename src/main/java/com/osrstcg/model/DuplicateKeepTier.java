package com.osrstcg.model;

import com.osrstcg.service.RarityMath;

public enum DuplicateKeepTier
{
    NONE(null),
	COMMON(RarityMath.Tier.COMMON),
	UNCOMMON(RarityMath.Tier.UNCOMMON),
	RARE(RarityMath.Tier.RARE),
	EPIC(RarityMath.Tier.EPIC),
	LEGENDARY(RarityMath.Tier.LEGENDARY),
	MYTHIC(RarityMath.Tier.MYTHIC),
	GODLY(RarityMath.Tier.GODLY);

	private final RarityMath.Tier tier;

	DuplicateKeepTier(RarityMath.Tier tier)
	{
		this.tier = tier;
	}

	public RarityMath.Tier toRarityTier()
	{
		return tier;
	}

	public String displayLabel()
	{
        if(tier == null) return "None";

		return tier.getLabel();
	}

	@Override
	public String toString()
	{
		return displayLabel();
	}
}

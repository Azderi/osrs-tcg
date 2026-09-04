package com.osrstcg.ui.shop;

import com.osrstcg.catalog.BoosterPackDefinition;
/** One shop tile's precomputed data: a booster definition plus its set-completion progress counts. */
public final class BoosterShopRow
{
	public final BoosterPackDefinition booster;
	public final int progressOwn;
	public final int progressFoilOwn;
	public final int progressTotal;
/** Stores the booster and progress counts verbatim. */
	public BoosterShopRow(BoosterPackDefinition booster, int progressOwn, int progressFoilOwn, int progressTotal)
	{
		this.booster = booster;
		this.progressOwn = progressOwn;
		this.progressFoilOwn = progressFoilOwn;
		this.progressTotal = progressTotal;
	}
}

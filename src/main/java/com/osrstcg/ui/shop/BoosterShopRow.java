package com.osrstcg.ui.shop;

import com.osrstcg.catalog.BoosterPackDefinition;

public final class BoosterShopRow
{
	public final BoosterPackDefinition booster;
	public final int progressOwn;
	public final int progressFoilOwn;
	public final int progressTotal;

	public BoosterShopRow(BoosterPackDefinition booster, int progressOwn, int progressFoilOwn, int progressTotal)
	{
		this.booster = booster;
		this.progressOwn = progressOwn;
		this.progressFoilOwn = progressFoilOwn;
		this.progressTotal = progressTotal;
	}
}

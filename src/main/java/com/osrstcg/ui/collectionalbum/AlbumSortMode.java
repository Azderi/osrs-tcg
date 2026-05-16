package com.osrstcg.ui.collectionalbum;

public enum AlbumSortMode
{
	SCORE_DESC("Score (high first)"),
	RARITY_DESC("Rarity (high first)"),
	NAME_ASC("Name (A–Z)");

	private final String label;

	AlbumSortMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

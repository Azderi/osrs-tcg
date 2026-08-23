package com.osrstcg.persist;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object for {@code saves.json}.
 */
public final class TcgSavesIndex
{
	private List<TcgSaveMetadataEntry> saves = new ArrayList<>();

	public List<TcgSaveMetadataEntry> getSaves()
	{
		return saves;
	}

	public void setSaves(List<TcgSaveMetadataEntry> saves)
	{
		this.saves = saves == null ? new ArrayList<>() : saves;
	}
}

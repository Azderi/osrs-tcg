package com.osrstcg.model;

public enum DuplicateKeepVersion
{
	OLDEST("Oldest"),
	NEWEST("Newest");

	private final String label;

	DuplicateKeepVersion(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

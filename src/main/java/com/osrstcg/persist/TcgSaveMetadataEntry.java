package com.osrstcg.persist;

/**
 * One row in {@code saves.json} for {@code tcg.save} or a hash-named snapshot.
 */
public final class TcgSaveMetadataEntry
{
	private String name;
	/** Legacy {@code saves.json} field; preferred via {@link #getName()}. */
	private String file;
	private int cardCount;
	private long credits;
	/** Content hash of the encoded blob (same as filename for snapshots). */
	private String hash;
	private String savedAt;
	private String trigger;

	public TcgSaveMetadataEntry()
	{
	}

	public TcgSaveMetadataEntry(String name, int cardCount, String savedAt, String trigger)
	{
		this(name, cardCount, 0L, null, savedAt, trigger);
	}

	public TcgSaveMetadataEntry(
		String name,
		int cardCount,
		long credits,
		String hash,
		String savedAt,
		String trigger)
	{
		this.name = name;
		this.file = null;
		this.cardCount = cardCount;
		this.credits = Math.max(0L, credits);
		this.hash = hash;
		this.savedAt = savedAt;
		this.trigger = trigger;
	}

	public String getName()
	{
		if (name != null && !name.isEmpty())
		{
			return name;
		}
		return file;
	}

	public void setName(String name)
	{
		this.name = name;
		this.file = null;
	}

	public int getCardCount()
	{
		return cardCount;
	}

	public void setCardCount(int cardCount)
	{
		this.cardCount = cardCount;
	}

	public long getCredits()
	{
		return credits;
	}

	public void setCredits(long credits)
	{
		this.credits = Math.max(0L, credits);
	}

	public String getHash()
	{
		return hash;
	}

	public void setHash(String hash)
	{
		this.hash = hash;
	}

	public String getSavedAt()
	{
		return savedAt;
	}

	public void setSavedAt(String savedAt)
	{
		this.savedAt = savedAt;
	}

	public String getTrigger()
	{
		return trigger;
	}

	public void setTrigger(String trigger)
	{
		this.trigger = trigger;
	}
}

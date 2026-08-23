package com.osrstcg.state;

/**
 * One card from a pack open. For cloud opens, rarity/score/art come from the server pull payload
 * ({@code tierLabel}, {@code score}, {@code imagePath}, {@code foilImagePath}, artist credits,
 * optional custom {@code examine}, {@code wikiPage}, optional display {@code name}) rather than local
 * score math or the global card-art overlay.
 */
public class PackCardResult
{
	/** Storage / collection identity (may be {@code npc:{id}}). */
	private final String cardName;
	/**
	 * Friendly OSRS display name from API {@code name}.
	 */
	private final String displayName;
	private final boolean foil;
	private final String instanceId;
	private final String tierLabel;
	private final long score;
	private final String imagePath;
	private final String foilImagePath;
	private final String artistName;
	private final String artistColor;
	private final String artistUrl;
	/** Custom examine from the pull when present; null → use catalog examine. */
	private final String examine;
	private final Double condition;
	private final String pulledBy;
	private final Long pulledAtEpochMs;
	private final String source;
	/** OSRS Wiki article title from catalog {@code wiki.page}, when present on the pull. */
	private final String wikiPage;

	public PackCardResult(String cardName, boolean foil)
	{
		this(cardName, foil, null, null, 0L, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public PackCardResult(
		String cardName,
		boolean foil,
		String instanceId,
		String tierLabel,
		long score,
		String imagePath,
		String foilImagePath,
		String artistName,
		String artistColor,
		String artistUrl,
		String examine,
		Double condition,
		String pulledBy,
		Long pulledAtEpochMs,
		String source,
		String wikiPage,
		String displayName)
	{
		this.cardName = cardName == null ? "" : cardName;
		this.displayName = blankToNull(displayName);
		this.foil = foil;
		this.instanceId = instanceId;
		this.tierLabel = tierLabel;
		this.score = Math.max(0L, score);
		this.imagePath = imagePath;
		this.foilImagePath = blankToNull(foilImagePath);
		this.artistName = blankToNull(artistName);
		this.artistColor = blankToNull(artistColor);
		this.artistUrl = blankToNull(artistUrl);
		this.examine = normalizeExamine(examine);
		this.condition = condition;
		this.pulledBy = pulledBy;
		this.pulledAtEpochMs = pulledAtEpochMs;
		this.source = source;
		this.wikiPage = blankToNull(wikiPage);
	}

	/** Copy with pack-open provenance filled in when the server omitted it. */
	public PackCardResult withProvenance(String pulledBy, long pulledAtEpochMs, String source)
	{
		return new PackCardResult(
			cardName,
			foil,
			instanceId,
			tierLabel,
			score,
			imagePath,
			foilImagePath,
			artistName,
			artistColor,
			artistUrl,
			examine,
			condition,
			pulledBy,
			pulledAtEpochMs,
			source,
			wikiPage,
			displayName);
	}

	private static String blankToNull(String value)
	{
		return value == null || value.isBlank() ? null : value.trim();
	}

	/**
	 * Non-blank custom examine; preserves internal {@code \n}. Only trims ends so line breaks are kept.
	 */
	private static String normalizeExamine(String value)
	{
		if (value == null)
		{
			return null;
		}
		String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
		String trimmed = normalized.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public String getCardName()
	{
		return cardName;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public boolean isFoil()
	{
		return foil;
	}

	public String getInstanceId()
	{
		return instanceId;
	}

	public String getTierLabel()
	{
		return tierLabel;
	}

	public long getScore()
	{
		return score;
	}

	public String getImagePath()
	{
		return imagePath;
	}

	public String getFoilImagePath()
	{
		return foilImagePath;
	}

	public String getArtistName()
	{
		return artistName;
	}

	public String getArtistColor()
	{
		return artistColor;
	}

	public String getArtistUrl()
	{
		return artistUrl;
	}

	public String getExamine()
	{
		return examine;
	}

	public Double getCondition()
	{
		return condition;
	}

	public String getPulledBy()
	{
		return pulledBy;
	}

	public Long getPulledAtEpochMs()
	{
		return pulledAtEpochMs;
	}

	public String getSource()
	{
		return source;
	}

	public String getWikiPage()
	{
		return wikiPage;
	}

	public boolean hasServerTier()
	{
		return tierLabel != null && !tierLabel.isBlank();
	}
}

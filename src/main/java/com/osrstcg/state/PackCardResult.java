package com.osrstcg.state;

import com.osrstcg.cloud.api.JsonObjects;
import lombok.Getter;

@Getter
public class PackCardResult
{
	private final String cardName;
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
	private final String examine;
	private final Double condition;
	private final String pulledBy;
	private final Long pulledAtEpochMs;
	private final String source;
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
		this.displayName = JsonObjects.blankToNull(displayName);
		this.foil = foil;
		this.instanceId = instanceId;
		this.tierLabel = tierLabel;
		this.score = Math.max(0L, score);
		this.imagePath = imagePath;
		this.foilImagePath = JsonObjects.blankToNull(foilImagePath);
		this.artistName = JsonObjects.blankToNull(artistName);
		this.artistColor = JsonObjects.blankToNull(artistColor);
		this.artistUrl = JsonObjects.blankToNull(artistUrl);
		this.examine = normalizeExamine(examine);
		this.condition = condition;
		this.pulledBy = pulledBy;
		this.pulledAtEpochMs = pulledAtEpochMs;
		this.source = source;
		this.wikiPage = JsonObjects.blankToNull(wikiPage);
	}

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

	public boolean hasServerTier()
	{
		return tierLabel != null && !tierLabel.isBlank();
	}
}

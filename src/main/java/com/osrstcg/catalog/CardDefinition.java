package com.osrstcg.catalog;

import com.google.gson.annotations.JsonAdapter;
import java.util.Collections;
import java.util.List;
import lombok.Data;
/**
 * Catalog definition of a single card: identity, artwork, rarity tier, and the raw/override
 * score fields used to compute display and pack-odds values.
 */
@Data
public class CardDefinition
{
	private Long id;
	private List<Long> variantIds;
	private String name;
	private String displayName;
	@JsonAdapter(CategoryListTypeAdapter.class)
	private List<String> category;
	@JsonAdapter(CategoryListTypeAdapter.class)
	private List<String> regions;
	private String imageUrl;
	private String foilImagePath;
	private String artistName;
	private String artistUrl;
	private String artistColor;
	private Integer level;
	private Long value;
	private Long score;
	private Long foilScore;
	private String tierLabel;
/**
	 * @deprecated Prefer {@link #score}; kept as an alias populated from {@code tcg.score}.
	 */
	@Deprecated
	private Long overrideScore;
/**
	 * @deprecated Prefer {@link #foilScore}.
	 */
	@Deprecated
	private Long overrideFoilScore;
	private String examine;
	private String wikiPage;
/**
	 * The score to display/use for odds math. When {@code foil} is true, prefers {@link #foilScore}
	 * then {@link #overrideFoilScore} (both must be non-negative); otherwise, and as a fallback,
	 * uses {@link #score} then {@link #overrideScore}, clamped to non-negative. Defaults to 0.
	 *
	 * @param foil whether to prefer the foil-specific score
	 * @return the resolved score, never negative
	 */
	public long displayScore(boolean foil)
	{
		if (foil)
		{
			if (foilScore != null && foilScore >= 0L)
			{
				return foilScore;
			}
			if (overrideFoilScore != null && overrideFoilScore >= 0L)
			{
				return overrideFoilScore;
			}
		}
		if (score != null)
		{
			return Math.max(0L, score);
		}
		if (overrideScore != null)
		{
			return Math.max(0L, overrideScore);
		}
		return 0L;
	}
/** {@link #category}, or an empty list if unset. */
	public List<String> getCategoryTags()
	{
		return category == null ? Collections.emptyList() : category;
	}
/** {@link #regions}, or an empty list if unset. */
	public List<String> getRegionTags()
	{
		return regions == null ? Collections.emptyList() : regions;
	}
/** Display label for the first part of the first category tag (e.g. "Skilling"), or "Unknown" if none. */
	public String getPrimaryCategory()
	{
		List<String> tags = getCategoryTags();
		if (tags.isEmpty())
		{
			return "Unknown";
		}
		List<String> parts = CategoryTagUtil.expandCompoundParts(tags.get(0));
		if (parts.isEmpty())
		{
			return "Unknown";
		}
		String canon = CategoryTagUtil.canonicalKey(parts.get(0));
		return CategoryTagUtil.toDisplayLabel(canon);
	}
}

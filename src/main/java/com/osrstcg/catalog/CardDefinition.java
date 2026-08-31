package com.osrstcg.catalog;

import com.google.gson.annotations.JsonAdapter;
import java.util.Collections;
import java.util.List;
import lombok.Data;

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
	private Boolean questItem;
	private String wikiPage;

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

	public List<String> getCategoryTags()
	{
		return category == null ? Collections.emptyList() : category;
	}

	public List<String> getRegionTags()
	{
		return regions == null ? Collections.emptyList() : regions;
	}

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

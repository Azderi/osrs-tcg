package com.osrstcg.catalog;

import com.google.gson.annotations.JsonAdapter;
import java.util.Collections;
import java.util.List;
import lombok.Data;

@Data
public class CardDefinition
{
	/** Live catalog numeric id (item/npc id). */
	private Long id;
	/**
	 * Related OSRS ids from live {@code tcg.variants[].id} (does not include {@link #id}).
	 * Used by the PluginMessage owned-id lists together with the parent id.
	 */
	private List<Long> variantIds;
	/**
	 * Storage / collection identity key (may be {@code npc:{id}} when an NPC shares an item name).
	 */
	private String name;
	/**
	 * Friendly OSRS display name. For colliding NPCs this is the raw live JSON {@code name}
	 * while {@link #name} holds {@code npc:{id}}. Null/blank → UI falls back to {@link #name}.
	 */
	private String displayName;
	@JsonAdapter(CategoryListTypeAdapter.class)
	private List<String> category;
	/**
	 * Geographic regions from live catalog {@code raw.regions} (items and NPCs); used with category tags for
	 * regional pack filters (same as server roller / front normalizeLive).
	 */
	@JsonAdapter(CategoryListTypeAdapter.class)
	private List<String> regions;
	private String imageUrl;
	/** Foil image path from overlay or pack pull; never from live JSON. */
	private String foilImagePath;
	private String artistName;
	private String artistUrl;
	private String artistColor;
	private Integer level;
	private Long value;
	/**
	 * Authoritative base display score from live {@code tcg.score} (exact - not client-rounded).
	 * {@link #overrideScore} mirrors this for older call sites.
	 */
	private Long score;
	/** Authoritative foil display score from live {@code tcg.foilScore}. */
	private Long foilScore;
	/** Authoritative rarity label from live {@code tcg.tierLabel}. */
	private String tierLabel;
	/**
	 * @deprecated Prefer {@link #score}; kept as an alias populated from {@code tcg.score}.
	 */
	@Deprecated
	private Long overrideScore;
	/**
	 * Optional editor override; live cards also set {@link #foilScore} to the same value when present.
	 * @deprecated Prefer {@link #foilScore}.
	 */
	@Deprecated
	private Long overrideFoilScore;
	private String examine;
	private Boolean questItem;
	/** OSRS Wiki article title from live catalog {@code wiki.page}, when present. */
	private String wikiPage;

	/**
	 * Display score for UI: foil uses {@link #foilScore} (else override), otherwise base {@link #score}.
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

	public List<String> getCategoryTags()
	{
		return category == null ? Collections.emptyList() : category;
	}

	public List<String> getRegionTags()
	{
		return regions == null ? Collections.emptyList() : regions;
	}

	/** First category tag (normalized display), for grouping and logging. */
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

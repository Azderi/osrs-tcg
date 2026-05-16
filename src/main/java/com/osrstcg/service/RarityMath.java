package com.osrstcg.service;

import com.osrstcg.data.CardDefinition;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class RarityMath
{
	public enum Tier
	{
		/** Marginal pack tier-roll probability; sums to 100%. Used for approximate "1 in N" on reveal. */
		COMMON("Common", new Color(0xFFFFFF), 36.0d),
		UNCOMMON("Uncommon", new Color(0x2ECC71), 32.0d),
		RARE("Rare", new Color(0x3498DB), 16.0d),
		EPIC("Epic", new Color(0x9B59B6), 8.0d),
		LEGENDARY("Legendary", new Color(0xE74C3C), 4.0d),
		MYTHIC("Mythic", new Color(0xFF6EC7), 2.0d),
		GODLY("Godly", new Color(0xF2C94C), 2.0d);

		private final String label;
		private final Color color;
		private final double chancePercent;

		Tier(String label, Color color, double chancePercent)
		{
			this.label = label;
			this.color = color;
			this.chancePercent = chancePercent;
		}

		public String getLabel()
		{
			return label;
		}

		public Color getColor()
		{
			return color;
		}

		public double getChancePercent()
		{
			return chancePercent;
		}
	}

	private RarityMath()
	{
	}

	private static final double MONSTER_LEVEL_SCORE_MULT = 1.5d;

	private static boolean isMonsterCard(CardDefinition card)
	{
		for (String t : card.getCategoryTags())
		{
			if (t != null && "monster".equals(t.toLowerCase(Locale.ROOT)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Level contribution to {@link #score} when {@link CardDefinition#getOverrideScore()} is null:
	 * {@code level²} for non-monsters; {@code (level²)×1.5} for Monster category tags.
	 */
	public static double levelBasedScore(CardDefinition card)
	{
		if (card == null || card.getLevel() == null)
		{
			return 0.0d;
		}
		double sq = Math.pow(card.getLevel(), 2);
		return isMonsterCard(card) ? sq * MONSTER_LEVEL_SCORE_MULT : sq;
	}

	/**
	 * {@code max(value, levelPart)} where {@code levelPart} is {@link CardDefinition#getOverrideScore()} when set,
	 * otherwise {@link #levelBasedScore}.
	 */
	public static double score(CardDefinition card)
	{
		if (card == null)
		{
			return 0.0d;
		}
		double valueScore = card.getValue() == null ? 0.0d : card.getValue();
		Long override = card.getOverrideScore();
		if (override != null)
		{
			return Math.max(valueScore, Math.max(0.0d, override.doubleValue()));
		}
		return Math.max(valueScore, levelBasedScore(card));
	}

	/**
	 * Foil contribution for collection score / card face: {@code round(score^1.1)} from {@link #score}.
	 */
	public static long foilAdjustedScoreRounded(CardDefinition card)
	{
		double s = score(card);
		if (s <= 0.0d)
		{
			return 0L;
		}
		return Math.round(Math.pow(s, 1.1d));
	}

	/**
	 * Relative pull weight within a tier pool when scores span a range: lowest score gets weight {@code 1}, highest
	 * gets {@code 1/rarityRatio} ({@code rarityRatio}× rarer), linear in score between. When {@code maxScore <= minScore},
	 * returns {@code 1} so all cards in the pool are equivalent for weighting.
	 *
	 * @param rarityRatio must be {@code >= 1} (e.g. {@code 3} for “top score is 3× rarer than bottom”).
	 */
	public static double linearTierPullWeightByScore(double score, double minScore, double maxScore, double rarityRatio)
	{
		if (rarityRatio < 1.0d)
		{
			rarityRatio = 1.0d;
		}
		if (!(maxScore > minScore))
		{
			return 1.0d;
		}
		double invR = 1.0d / rarityRatio;
		double t = (score - minScore) / (maxScore - minScore);
		if (t < 0.0d)
		{
			t = 0.0d;
		}
		else if (t > 1.0d)
		{
			t = 1.0d;
		}
		return 1.0d - (1.0d - invR) * t;
	}

	/**
	 * Cards with GE {@linkplain CardDefinition#getValue() value} {@code 0} or {@code 1} are always {@link Tier#COMMON}
	 * for display/pack tiering and are omitted from the per-category percentile ordering so other cards keep the same
	 * relative tier cutoffs among themselves.
	 */
	public static boolean isLowValueTierExempt(Long value)
	{
		return value != null && (value == 0L || value == 1L);
	}

	public static Tier tierForPercentile(double percentile)
	{
		if (percentile >= 0.98d)
		{
			return Tier.GODLY;
		}
		if (percentile >= 0.95d)
		{
			return Tier.MYTHIC;
		}
		if (percentile >= 0.90d)
		{
			return Tier.LEGENDARY;
		}
		if (percentile >= 0.75d)
		{
			return Tier.EPIC;
		}
		if (percentile >= 0.50d)
		{
			return Tier.RARE;
		}
		if (percentile >= 0.25d)
		{
			return Tier.UNCOMMON;
		}
		return Tier.COMMON;
	}

	/**
	 * After percentile tiers are assigned on a list sorted by {@link #score} ascending:
	 * <ul>
	 *   <li>All cards with the same non-null {@link CardDefinition#getValue()} get the best tier among that value.</li>
	 *   <li>All cards with the same rounded score get the best tier in that score run (covers null {@code value}).</li>
	 * </ul>
	 */
	public static void unifyTiersForValueAndScoreTies(List<CardDefinition> sortedByScoreAscending, Map<CardDefinition, Tier> tierByCard)
	{
		if (sortedByScoreAscending == null || sortedByScoreAscending.isEmpty() || tierByCard == null)
		{
			return;
		}

		Map<Long, Tier> bestByExactValue = new HashMap<>();
		for (CardDefinition c : sortedByScoreAscending)
		{
			Long v = c == null ? null : c.getValue();
			if (v == null)
			{
				continue;
			}
			Tier t = tierByCard.get(c);
			if (t == null)
			{
				continue;
			}
			bestByExactValue.merge(v, t, RarityMath::maxTier);
		}
		for (CardDefinition c : sortedByScoreAscending)
		{
			if (c == null)
			{
				continue;
			}
			Long v = c.getValue();
			if (v != null)
			{
				Tier best = bestByExactValue.get(v);
				if (best != null)
				{
					tierByCard.put(c, best);
				}
			}
		}

		int i = 0;
		while (i < sortedByScoreAscending.size())
		{
			CardDefinition first = sortedByScoreAscending.get(i);
			int j = i + 1;
			long scoreKey = Math.round(score(first));
			while (j < sortedByScoreAscending.size()
				&& Math.round(score(sortedByScoreAscending.get(j))) == scoreKey)
			{
				j++;
			}
			Tier maxInRun = Tier.COMMON;
			for (int k = i; k < j; k++)
			{
				CardDefinition c = sortedByScoreAscending.get(k);
				Tier t = tierByCard.get(c);
				if (t != null)
				{
					maxInRun = maxTier(maxInRun, t);
				}
			}
			for (int k = i; k < j; k++)
			{
				CardDefinition c = sortedByScoreAscending.get(k);
				if (c != null)
				{
					tierByCard.put(c, maxInRun);
				}
			}
			i = j;
		}
	}

	/**
	 * Display rarity used by the collection album: score percentiles within each {@linkplain CardDefinition#getPrimaryCategory()
	 * primary category} on cards that are not {@linkplain #isLowValueTierExempt(Long) low-GE-value exempt} (value {@code 0}
	 * or {@code 1} are forced to {@link Tier#COMMON} and left out of that percentile pool), then
	 * {@linkplain #unifyTiersForValueAndScoreTies(List, Map) tie unification} inside that tiering subset, then
	 * {@linkplain #unifyTiersGloballyByExactCardValue(List, Map) global lift} by exact GE value. Pack reveal and pack rolls use
	 * the same mapping when built from the same card universe (typically the full loaded catalog).
	 */
	public static Map<String, Tier> displayTierByCardName(List<CardDefinition> allCards)
	{
		Map<String, Tier> tierByName = new HashMap<>();
		if (allCards == null || allCards.isEmpty())
		{
			return tierByName;
		}

		Map<String, List<CardDefinition>> byCategory = allCards.stream()
			.filter(card -> card != null && card.getName() != null && !card.getName().trim().isEmpty())
			.collect(Collectors.groupingBy(CardDefinition::getPrimaryCategory));

		for (List<CardDefinition> pool : byCategory.values())
		{
			if (pool.isEmpty())
			{
				continue;
			}
			List<CardDefinition> tiering = new ArrayList<>();
			for (CardDefinition card : pool)
			{
				if (card == null)
				{
					continue;
				}
				if (isLowValueTierExempt(card.getValue()))
				{
					String name = card.getName();
					if (name != null && !name.trim().isEmpty())
					{
						tierByName.put(name, Tier.COMMON);
					}
					continue;
				}
				tiering.add(card);
			}
			if (tiering.isEmpty())
			{
				continue;
			}
			List<CardDefinition> sorted = new ArrayList<>(tiering);
			sorted.sort((a, b) -> Double.compare(score(a), score(b)));
			int size = sorted.size();
			Map<CardDefinition, Tier> tierByCard = new HashMap<>();
			for (int i = 0; i < size; i++)
			{
				CardDefinition card = sorted.get(i);
				double percentile = size == 1 ? 1.0d : ((double) i / (double) (size - 1));
				tierByCard.put(card, tierForPercentile(percentile));
			}
			unifyTiersForValueAndScoreTies(sorted, tierByCard);
			for (int i = 0; i < size; i++)
			{
				CardDefinition card = sorted.get(i);
				Tier tier = tierByCard.getOrDefault(card, Tier.COMMON);
				tierByName.put(card.getName(), tier);
			}
		}

		unifyTiersGloballyByExactCardValue(allCards, tierByName);

		for (CardDefinition c : allCards)
		{
			if (c == null || c.getName() == null || c.getName().trim().isEmpty())
			{
				continue;
			}
			if (isLowValueTierExempt(c.getValue()))
			{
				tierByName.put(c.getName(), Tier.COMMON);
			}
		}

		return tierByName;
	}

	/**
	 * After per-card tiers are known (e.g. per-category percentiles), lift every card that shares the same
	 * non-null {@link CardDefinition#getValue()} to the best tier seen for that value anywhere in the list.
	 */
	public static void unifyTiersGloballyByExactCardValue(List<CardDefinition> allCards, Map<String, Tier> tierByName)
	{
		if (allCards == null || allCards.isEmpty() || tierByName == null)
		{
			return;
		}
		Map<Long, Tier> bestByValue = new HashMap<>();
		for (CardDefinition c : allCards)
		{
			if (c == null || c.getName() == null || c.getName().trim().isEmpty())
			{
				continue;
			}
			Long v = c.getValue();
			if (v == null)
			{
				continue;
			}
			Tier t = tierByName.get(c.getName());
			if (t == null)
			{
				continue;
			}
			bestByValue.merge(v, t, RarityMath::maxTier);
		}
		for (CardDefinition c : allCards)
		{
			if (c == null || c.getName() == null || c.getName().trim().isEmpty())
			{
				continue;
			}
			Long v = c.getValue();
			if (v == null)
			{
				continue;
			}
			Tier best = bestByValue.get(v);
			if (best != null)
			{
				tierByName.put(c.getName(), best);
			}
		}
	}

	public static Tier maxTier(Tier a, Tier b)
	{
		if (a == null)
		{
			return b == null ? Tier.COMMON : b;
		}
		if (b == null)
		{
			return a;
		}
		return a.ordinal() >= b.ordinal() ? a : b;
	}

	static long denominatorForTierCard(Tier tier, int cardsInTier)
	{
		int safeCards = Math.max(1, cardsInTier);
		double tierChance = Math.max(0.0001d, tier.getChancePercent() / 100.0d);
		return Math.max(1L, Math.round((double) safeCards / tierChance));
	}
}

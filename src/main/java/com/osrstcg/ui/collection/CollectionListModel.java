package com.osrstcg.ui.collection;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CollectionState;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.catalog.RarityMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CollectionListModel
{
	public enum SortMode
	{
		SCORE_DESC("Score (high → low)"),
		SCORE_ASC("Score (low → high)"),
		NAME_AZ("Name (A → Z)"),
		NAME_ZA("Name (Z → A)"),
		PULLED_DESC("Pulled at (newest)"),
		PULLED_ASC("Pulled at (oldest)");

		private final String label;

		SortMode(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public static final class Row
	{
		private final String name;
		private final boolean foil;
		private final RarityMath.Tier tier;
		private final long score;
		private final long pulledAtEpochMs;

		public Row(String name, boolean foil, RarityMath.Tier tier, long score, long pulledAtEpochMs)
		{
			this.name = name == null ? "" : name;
			this.foil = foil;
			this.tier = tier == null ? RarityMath.Tier.COMMON : tier;
			this.score = Math.max(0L, score);
			this.pulledAtEpochMs = Math.max(0L, pulledAtEpochMs);
		}

		public String getName()
		{
			return name;
		}

		public boolean isFoil()
		{
			return foil;
		}

		public RarityMath.Tier getTier()
		{
			return tier;
		}

		public long getScore()
		{
			return score;
		}

		public long getPulledAtEpochMs()
		{
			return pulledAtEpochMs;
		}
	}

	private CollectionListModel()
	{
	}

	public static List<Row> buildRows(
		CollectionState collection,
		Map<String, CardDefinition> cardsByLowerName,
		Set<String> packEligibleNamesOrNull,
		RarityMath.Tier rarityFilterOrNull,
		String nameQueryOrNull,
		SortMode sortMode)
	{
		Map<CardCollectionKey, Long> maxPulledAt = new HashMap<>();
		aggregateOwnedExcludingBeta(collection, maxPulledAt);

		String query = nameQueryOrNull == null ? "" : nameQueryOrNull.trim().toLowerCase(Locale.ROOT);

		List<Row> rows = new ArrayList<>(maxPulledAt.size());
		for (Map.Entry<CardCollectionKey, Long> entry : maxPulledAt.entrySet())
		{
			CardCollectionKey key = entry.getKey();
			if (key == null)
			{
				continue;
			}
			String name = key.getCardName();
			if (name == null || name.isBlank())
			{
				continue;
			}
			String trimmed = name.trim();
			if (packEligibleNamesOrNull != null && !packEligibleNamesOrNull.contains(trimmed))
			{
				continue;
			}

			CardDefinition def = cardsByLowerName == null
				? null
				: cardsByLowerName.get(trimmed.toLowerCase(Locale.ROOT));
			RarityMath.Tier tier = def == null
				? RarityMath.Tier.COMMON
				: RarityMath.tierFromLabel(def.getTierLabel());
			if (rarityFilterOrNull != null && tier != rarityFilterOrNull)
			{
				continue;
			}
			if (!query.isEmpty() && !nameMatchesQuery(trimmed, def, query))
			{
				continue;
			}

			long score = def == null ? 0L : def.displayScore(key.isFoil());
			rows.add(new Row(trimmed, key.isFoil(), tier, score, entry.getValue()));
		}

		SortMode mode = sortMode == null ? SortMode.SCORE_DESC : sortMode;
		rows.sort(comparatorFor(mode));
		return rows;
	}

	private static boolean nameMatchesQuery(String cardName, CardDefinition def, String queryLower)
	{
		if (cardName != null && cardName.toLowerCase(Locale.ROOT).contains(queryLower))
		{
			return true;
		}
		if (def != null && def.getDisplayName() != null
			&& def.getDisplayName().toLowerCase(Locale.ROOT).contains(queryLower))
		{
			return true;
		}
		return false;
	}

	public static Set<String> eligibleNamesForPack(
		BoosterPackDefinition booster,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
	{
		Set<String> eligible = new HashSet<>();
		if (booster == null)
		{
			return eligible;
		}
		List<String> filters = booster.getCategoryFilters();
		List<CardDefinition> source = filters.isEmpty() ? rollPool : allCards;
		if (source == null)
		{
			return eligible;
		}
		for (CardDefinition c : source)
		{
			if (c == null || c.getName() == null || c.getName().trim().isEmpty())
			{
				continue;
			}
			if (filters.isEmpty() || BoosterPackDefinition.cardMatchesRegion(c, filters))
			{
				eligible.add(c.getName().trim());
			}
		}
		return eligible;
	}

	public static Map<String, CardDefinition> indexByLowerName(List<CardDefinition> cards)
	{
		Map<String, CardDefinition> map = new HashMap<>();
		if (cards == null)
		{
			return map;
		}
		for (CardDefinition c : cards)
		{
			if (c == null || c.getName() == null || c.getName().isBlank())
			{
				continue;
			}
			map.putIfAbsent(c.getName().trim().toLowerCase(Locale.ROOT), c);
		}
		return map;
	}

	private static void aggregateOwnedExcludingBeta(
		CollectionState collection,
		Map<CardCollectionKey, Long> maxPulledAtOut)
	{
		if (collection == null)
		{
			return;
		}
		for (OwnedCardInstance i : collection.getOwnedInstances())
		{
			if (i == null || i.isBeta() || i.getCardName() == null || i.getCardName().isBlank())
			{
				continue;
			}
			CardCollectionKey key = new CardCollectionKey(i.getCardName().trim(), i.isFoil());
			maxPulledAtOut.merge(key, i.getPulledAtEpochMs(), Math::max);
		}
	}

	private static Comparator<Row> comparatorFor(SortMode mode)
	{
		Comparator<Row> byName = Comparator.comparing(r -> r.getName().toLowerCase(Locale.ROOT));
		Comparator<Row> byScore = Comparator.comparingLong(Row::getScore);
		Comparator<Row> byPulled = Comparator.comparingLong(Row::getPulledAtEpochMs);
		Comparator<Row> foilLast = Comparator.comparing(Row::isFoil);
		switch (mode)
		{
			case SCORE_ASC:
				return byScore.thenComparing(byName).thenComparing(foilLast);
			case NAME_AZ:
				return byName.thenComparing(foilLast).thenComparing(byScore.reversed());
			case NAME_ZA:
				return byName.reversed().thenComparing(foilLast).thenComparing(byScore.reversed());
			case PULLED_DESC:
				return byPulled.reversed().thenComparing(byName).thenComparing(foilLast);
			case PULLED_ASC:
				return byPulled.thenComparing(byName).thenComparing(foilLast);
			case SCORE_DESC:
			default:
				return byScore.reversed().thenComparing(byName).thenComparing(foilLast);
		}
	}
}

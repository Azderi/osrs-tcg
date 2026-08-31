package com.osrstcg.interop;

import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CloudSidebarCollectionStats;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrstcg.catalog.CollectionSetCompletionUtil;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.layout.PackCloseSnapshot;

/**
 * Computes the same collection overview numbers as the plugin panel (roll pool, owned map, score rules).
 */
@Singleton
public class TcgPublicStatsCalculator
{
	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;

	@Inject
	public TcgPublicStatsCalculator(TcgStateService stateService, CardDatabase cardDatabase)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
	}

	/**
	 * Local collection overview using the same rules as the sidebar fallback (roll pool, score).
	 * Used to detect drift vs server inbox {@code stats}.
	 */
	public CloudSidebarCollectionStats computeLocalSidebarStats()
	{
		Map<CardCollectionKey, Integer> owned;
		synchronized (stateService)
		{
			owned = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
		}
		List<CardDefinition> all = cardDatabase.getCards();
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(all);
		return computeLocalOverview(owned, all, rollPool);
	}

	/**
	 * Sidebar overview: prefer the snapshot's cloud/local stats when present, otherwise compute locally.
	 */
	public static CloudSidebarCollectionStats resolveOverview(
		PackCloseSnapshot snap,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
	{
		if (snap != null && snap.collectionStats != null)
		{
			return snap.collectionStats;
		}
		return computeLocalOverview(snap == null ? null : snap.owned, allCards, rollPool);
	}

	/**
	 * Shared local overview math for the sidebar and public stats (roll pool filter + score rules).
	 */
	public static CloudSidebarCollectionStats computeLocalOverview(
		Map<CardCollectionKey, Integer> owned,
		List<CardDefinition> allCards,
		List<CardDefinition> rollPool)
	{
		final Map<CardCollectionKey, Integer> ownedMap = owned == null ? Map.of() : owned;
		if (allCards == null)
		{
			allCards = List.of();
		}
		if (rollPool == null)
		{
			rollPool = List.of();
		}

		Set<String> rollPoolNames = new HashSet<>();
		for (CardDefinition c : rollPool)
		{
			if (c != null && c.getName() != null)
			{
				rollPoolNames.add(c.getName());
			}
		}

		int uniqueOwned = (int) CollectionSetCompletionUtil.collectedNamesFromOwned(ownedMap).stream()
			.filter(rollPoolNames::contains)
			.count();
		int totalCardsOwned = ownedMap.entrySet().stream()
			.filter(e -> e.getKey().getCardName() != null && rollPoolNames.contains(e.getKey().getCardName()))
			.mapToInt(e -> e.getValue() == null ? 0 : e.getValue())
			.sum();
		long foilOwned = 0L;
		for (Map.Entry<CardCollectionKey, Integer> e : ownedMap.entrySet())
		{
			if (e.getKey().isFoil()
				&& e.getKey().getCardName() != null
				&& rollPoolNames.contains(e.getKey().getCardName()))
			{
				foilOwned += e.getValue() == null ? 0L : e.getValue();
			}
		}
		int uniqueFoilOwned = (int) ownedMap.keySet().stream()
			.filter(k -> k.isFoil()
				&& k.getCardName() != null
				&& rollPoolNames.contains(k.getCardName()))
			.filter(k ->
			{
				Integer qty = ownedMap.get(k);
				return qty != null && qty > 0;
			})
			.count();
		int totalCardPool = rollPool.size();
		double completionPct = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueOwned) / totalCardPool;
		double foilCompletionPct = totalCardPool <= 0 ? 0.0d : (100.0d * uniqueFoilOwned) / totalCardPool;

		Set<String> collectedNames = CollectionSetCompletionUtil.collectedNamesFromOwned(ownedMap);
		Map<String, CardDefinition> defByLower = new HashMap<>();
		for (CardDefinition c : allCards)
		{
			if (c != null && c.getName() != null)
			{
				defByLower.putIfAbsent(c.getName().toLowerCase(Locale.ROOT), c);
			}
		}
		long collectionScore = 0L;
		for (String cardName : collectedNames)
		{
			if (cardName == null || !rollPoolNames.contains(cardName))
			{
				continue;
			}
			CardDefinition def = defByLower.get(cardName.toLowerCase(Locale.ROOT));
			if (def == null)
			{
				continue;
			}
			boolean hasFoil = CollectionSetCompletionUtil.hasFoilOwned(ownedMap, cardName);
			collectionScore += def.displayScore(hasFoil);
		}

		return new CloudSidebarCollectionStats(
			uniqueOwned,
			uniqueFoilOwned,
			totalCardsOwned,
			foilOwned,
			totalCardPool,
			completionPct,
			foilCompletionPct,
			collectionScore);
	}
}

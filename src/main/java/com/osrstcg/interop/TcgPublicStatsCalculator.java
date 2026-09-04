package com.osrstcg.interop;

import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.TcgPublicStats;
import com.osrstcg.state.TcgState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrstcg.catalog.CollectionSetCompletionUtil;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.layout.PackCloseSnapshot;
/**
 * Computes {@link TcgPublicStats}/{@link CloudSidebarCollectionStats} summaries of the local player's
 * collection, for sharing with other players/plugins (e.g. chat stats, sidebar overview).
 */
@Singleton
public class TcgPublicStatsCalculator
{
	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;
/** Stores the state service and card database used to compute stats. */
	@Inject
	public TcgPublicStatsCalculator(TcgStateService stateService, CardDatabase cardDatabase)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
	}
/** Computes shareable stats for the current player from cloud overview data if available, else locally. */
	public TcgPublicStats computeLive()
	{
		CloudSidebarCollectionStats cloud = stateService.getCloudCollectionStats();
		Map<CardCollectionKey, Integer> owned;
		long openedPacks;
		synchronized (stateService)
		{
			TcgState s = stateService.getState();
			owned = new HashMap<>(s.getCollectionState().getOwnedCards());
			openedPacks = s.getEconomyState().getOpenedPacks();
		}
		if (cloud != null)
		{
			return new TcgPublicStats(
				cloud.getCollectionScore(),
				cloud.getCompletionPct(),
				cloud.getUniqueOwned(),
				cloud.getUniqueFoilOwned(),
				cloud.getFoilCompletionPct(),
				cloud.getTotalCardPool(),
				openedPacks,
				cloud.getTotalCardsOwned(),
				cloud.getFoilOwned(),
				false);
		}
		List<CardDefinition> all = cardDatabase.getCards();
		List<CardDefinition> rollPool = all;
		CloudSidebarCollectionStats overview = computeLocalOverview(owned, all, rollPool);
		return new TcgPublicStats(
			overview.getCollectionScore(),
			overview.getCompletionPct(),
			overview.getUniqueOwned(),
			overview.getUniqueFoilOwned(),
			overview.getFoilCompletionPct(),
			overview.getTotalCardPool(),
			openedPacks,
			overview.getTotalCardsOwned(),
			overview.getFoilOwned(),
			false);
	}
/** Computes a fresh local {@link CloudSidebarCollectionStats} overview from the current owned collection. */
	public CloudSidebarCollectionStats computeLocalSidebarStats()
	{
		Map<CardCollectionKey, Integer> owned;
		synchronized (stateService)
		{
			owned = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
		}
		List<CardDefinition> all = cardDatabase.getCards();
		List<CardDefinition> rollPool = all;
		return computeLocalOverview(owned, all, rollPool);
	}
/** Uses {@code snap}'s pre-computed overview if it has one, else computes it locally from {@code snap.owned}. */
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
	 * Derives a {@link CloudSidebarCollectionStats} overview from {@code owned}, restricted to cards in
	 * {@code rollPool}: unique/foil counts, total copies, completion percentages, and the display-score sum.
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

package com.runelitetcg.service;

import com.runelitetcg.data.BoosterPackDefinition;
import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.PackCardResult;
import com.runelitetcg.model.PackOpenResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class PackOpeningService
{
	private static final int DEFAULT_PACK_SIZE = 5;

	/**
	 * For Legendary / Mythic / Godly regional picks, weight by {@link RarityMath#score}: lowest score in the tier pool
	 * is {@code this}× as likely as the highest (linear between).
	 */
	private static final double TOP_TIER_SCORE_PULL_RARITY_RATIO = 3.0d;

	/** Reserved pack id for the free debug booster (only usable when debug logging is enabled in saved state). */
	public static final String DEBUG_PACK_ID = "runelitetcg_debug_pack";

	private final CardDatabase cardDatabase;
	private final TcgStateService stateService;
	private final TcgPartyAnnouncer partyAnnouncer;
	private final Random random;

	@Inject
	public PackOpeningService(CardDatabase cardDatabase, TcgStateService stateService,
		TcgPartyAnnouncer partyAnnouncer)
	{
		this(cardDatabase, stateService, partyAnnouncer, new Random());
	}

	PackOpeningService(CardDatabase cardDatabase, TcgStateService stateService,
		TcgPartyAnnouncer partyAnnouncer, Random random)
	{
		this.cardDatabase = cardDatabase;
		this.stateService = stateService;
		this.partyAnnouncer = partyAnnouncer;
		this.random = random;
	}

	public static boolean isDebugPack(BoosterPackDefinition booster)
	{
		return booster != null && DEBUG_PACK_ID.equals(booster.getId());
	}

	public PackOpenResult buyAndOpenPack(BoosterPackDefinition booster)
	{
		cardDatabase.load();
		long creditsBefore = stateService.getCredits();
		if (booster == null)
		{
			return PackOpenResult.failed("No booster pack selected.", creditsBefore, 0);
		}

		boolean debugPack = isDebugPack(booster) && stateService.isDebugLogging();
		if (isDebugPack(booster) && !debugPack)
		{
			return PackOpenResult.failed("Debug pack is only available when debug logging is enabled.", creditsBefore, 0);
		}

		int packPrice = booster.getPrice();
		if (!debugPack && packPrice <= 0)
		{
			return PackOpenResult.failed("Invalid pack price.", creditsBefore, packPrice);
		}

		if (creditsBefore < packPrice)
		{
			return PackOpenResult.failed("Not enough credits.", creditsBefore, packPrice);
		}

		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(cardDatabase.getCards());
		List<String> regionFilters = booster.getCategoryFilters();
		List<CardDefinition> pool = new ArrayList<>();
		for (CardDefinition card : rollPool)
		{
			if (BoosterPackDefinition.cardMatchesRegion(card, regionFilters))
			{
				pool.add(card);
			}
		}

		if (pool.isEmpty())
		{
			return PackOpenResult.failed("No cards in this booster pool.", creditsBefore, packPrice);
		}

		List<PackCardResult> pulls = debugPack ? rollDebugSameCardPack(pool) : rollPack(pool, rollPool, DEFAULT_PACK_SIZE);
		Map<CardCollectionKey, Integer> ownedBefore;
		synchronized (stateService)
		{
			ownedBefore = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
		}
		if (!stateService.applyPackOpenTransaction(packPrice, pulls, debugPack))
		{
			return PackOpenResult.failed("Pack transaction failed.", creditsBefore, packPrice);
		}

		if (partyAnnouncer != null)
		{
			Map<CardCollectionKey, Integer> ownedAfter;
			synchronized (stateService)
			{
				ownedAfter = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
			}
			for (String category : CollectionSetCompletionUtil.newlyCompletedPrimaryCategories(ownedBefore, ownedAfter, rollPool))
			{
				partyAnnouncer.announceCollectionSetComplete(category);
			}
		}

		long creditsAfter = stateService.getCredits();
		String packId = booster.getId() == null ? "" : booster.getId().trim();
		return PackOpenResult.succeeded("Pack opened.", creditsBefore, creditsAfter, packPrice, pulls,
			booster.getName(), packId);
	}

	private List<PackCardResult> rollDebugSameCardPack(List<CardDefinition> pool)
	{
		List<CardDefinition> valid = new ArrayList<>();
		for (CardDefinition c : pool)
		{
			if (c == null)
			{
				continue;
			}
			String n = c.getName();
			if (n != null && !n.trim().isEmpty())
			{
				valid.add(c);
			}
		}
		if (valid.isEmpty())
		{
			return List.of();
		}
		CardDefinition pick = valid.get(random.nextInt(valid.size()));
		String name = pick.getName();
		int foilPercent = stateService.getState().getRewardTuning().getFoilChancePercent();
		double foilChance = Math.max(0, Math.min(100, foilPercent)) / 100.0d;
		List<PackCardResult> pulls = new ArrayList<>(DEFAULT_PACK_SIZE);
		for (int i = 0; i < DEFAULT_PACK_SIZE; i++)
		{
			boolean foil = random.nextDouble() < foilChance;
			pulls.add(new PackCardResult(name, foil));
		}
		return pulls;
	}

	/**
	 * Tier rolls use {@link RarityMath#displayTierByCardName(List)} on the full loaded catalog (same as the collection album);
	 * pulls are only from {@code regionalPool}.
	 */
	private List<PackCardResult> rollPack(List<CardDefinition> regionalPool, List<CardDefinition> globalRollPool, int packSize)
	{
		List<PackCardResult> pulls = new ArrayList<>(packSize);
		int foilPercent = stateService.getState().getRewardTuning().getFoilChancePercent();
		double foilChance = Math.max(0, Math.min(100, foilPercent)) / 100.0d;
		Map<CardDefinition, RarityMath.Tier> globalTierByCard = buildGlobalTierByCard(globalRollPool);
		Map<RarityMath.Tier, List<CardDefinition>> regionalByGlobalTier = partitionRegionalByGlobalTier(regionalPool, globalTierByCard);

		for (int i = 0; i < packSize; i++)
		{
			CardDefinition selected = pickByTierChance(regionalPool, regionalByGlobalTier);
			boolean foil = random.nextDouble() < foilChance;
			pulls.add(new PackCardResult(selected.getName(), foil));
		}
		return pulls;
	}

	private Map<CardDefinition, RarityMath.Tier> buildGlobalTierByCard(List<CardDefinition> globalRollPool)
	{
		Map<CardDefinition, RarityMath.Tier> map = new IdentityHashMap<>();
		if (globalRollPool == null || globalRollPool.isEmpty())
		{
			return map;
		}

		Map<String, RarityMath.Tier> tierByName = RarityMath.displayTierByCardName(cardDatabase.getCards());
		for (CardDefinition card : globalRollPool)
		{
			if (card == null || card.getName() == null || card.getName().trim().isEmpty())
			{
				continue;
			}
			map.put(card, tierByName.getOrDefault(card.getName(), RarityMath.Tier.COMMON));
		}
		return map;
	}

	private static Map<RarityMath.Tier, List<CardDefinition>> partitionRegionalByGlobalTier(
		List<CardDefinition> regionalPool,
		Map<CardDefinition, RarityMath.Tier> globalTierByCard)
	{
		Map<RarityMath.Tier, List<CardDefinition>> out = new EnumMap<>(RarityMath.Tier.class);
		for (RarityMath.Tier tier : RarityMath.Tier.values())
		{
			out.put(tier, new ArrayList<>());
		}
		for (CardDefinition card : regionalPool)
		{
			RarityMath.Tier tier = globalTierByCard.getOrDefault(card, RarityMath.Tier.COMMON);
			out.get(tier).add(card);
		}
		return out;
	}

	private CardDefinition pickByTierChance(List<CardDefinition> fallbackPool, Map<RarityMath.Tier, List<CardDefinition>> regionalByGlobalTier)
	{
		if (fallbackPool.isEmpty())
		{
			return null;
		}

		for (int attempts = 0; attempts < 8; attempts++)
		{
			RarityMath.Tier tier = rollTier();
			List<CardDefinition> tierCards = regionalByGlobalTier.get(tier);
			if (tierCards != null && !tierCards.isEmpty())
			{
				return pickFromTierList(tierCards, tier);
			}
		}

		return fallbackPool.get(random.nextInt(fallbackPool.size()));
	}

	private static boolean tierUsesScoreWeightedPull(RarityMath.Tier tier)
	{
		return tier == RarityMath.Tier.LEGENDARY
			|| tier == RarityMath.Tier.GODLY
			|| tier == RarityMath.Tier.MYTHIC;
	}

	private CardDefinition pickFromTierList(List<CardDefinition> tierCards, RarityMath.Tier tier)
	{
		if (tierCards.size() == 1)
		{
			return tierCards.get(0);
		}
		if (!tierUsesScoreWeightedPull(tier))
		{
			return tierCards.get(random.nextInt(tierCards.size()));
		}

		double minScore = Double.POSITIVE_INFINITY;
		double maxScore = Double.NEGATIVE_INFINITY;
		for (CardDefinition c : tierCards)
		{
			if (c == null)
			{
				continue;
			}
			double s = RarityMath.score(c);
			if (s < minScore)
			{
				minScore = s;
			}
			if (s > maxScore)
			{
				maxScore = s;
			}
		}
		if (minScore == Double.POSITIVE_INFINITY)
		{
			return tierCards.get(random.nextInt(tierCards.size()));
		}

		double totalW = 0.0d;
		double[] weights = new double[tierCards.size()];
		for (int i = 0; i < tierCards.size(); i++)
		{
			CardDefinition c = tierCards.get(i);
			double s = c == null ? minScore : RarityMath.score(c);
			double w = RarityMath.linearTierPullWeightByScore(s, minScore, maxScore, TOP_TIER_SCORE_PULL_RARITY_RATIO);
			weights[i] = w;
			totalW += w;
		}
		if (totalW <= 0.0d)
		{
			return tierCards.get(random.nextInt(tierCards.size()));
		}

		double r = random.nextDouble() * totalW;
		double acc = 0.0d;
		for (int i = 0; i < weights.length; i++)
		{
			acc += weights[i];
			if (r < acc)
			{
				return tierCards.get(i);
			}
		}
		return tierCards.get(tierCards.size() - 1);
	}

	/**
	 * Low roll = rarer: Godly &lt; 2%, Mythic &lt; 4%, …, Common &lt; 100% of {@code [0, 100)}.
	 */
	private RarityMath.Tier rollTier()
	{
		double roll = random.nextDouble() * 100.0d;
		if (roll < 2.0d)
		{
			return RarityMath.Tier.GODLY;
		}
		if (roll < 4.0d)
		{
			return RarityMath.Tier.MYTHIC;
		}
		if (roll < 8.0d)
		{
			return RarityMath.Tier.LEGENDARY;
		}
		if (roll < 16.0d)
		{
			return RarityMath.Tier.EPIC;
		}
		if (roll < 32.0d)
		{
			return RarityMath.Tier.RARE;
		}
		if (roll < 64.0d)
		{
			return RarityMath.Tier.UNCOMMON;
		}
		return RarityMath.Tier.COMMON;
	}
}

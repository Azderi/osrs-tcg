package com.runelitetcg.service;

import com.runelitetcg.data.BoosterPackDefinition;
import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.RuneLiteTcgConfig;
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
import net.runelite.api.Client;
import net.runelite.client.util.Text;

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

	/** Normal packs: chance all five pulls are restricted to top three display tiers (Legendary / Mythic / Godly). */
	private static final int APEX_PACK_CHANCE_DENOMINATOR = 3000;

	/** Foil probability multiplier while opening an apex pack (capped at 100%). */
	private static final double APEX_PACK_FOIL_CHANCE_MULTIPLIER = 5.0d;

	private final CardDatabase cardDatabase;
	private final TcgStateService stateService;
	private final Client client;
	private final TcgPartyAnnouncer partyAnnouncer;
	private final PackSafeModeService packSafeModeService;
	private final Random random;

	@Inject
	public PackOpeningService(CardDatabase cardDatabase, TcgStateService stateService, Client client,
		TcgPartyAnnouncer partyAnnouncer, PackSafeModeService packSafeModeService)
	{
		this(cardDatabase, stateService, client, partyAnnouncer, packSafeModeService, new Random());
	}

	PackOpeningService(CardDatabase cardDatabase, TcgStateService stateService, Client client,
		TcgPartyAnnouncer partyAnnouncer, PackSafeModeService packSafeModeService, Random random)
	{
		this.cardDatabase = cardDatabase;
		this.stateService = stateService;
		this.client = client;
		this.partyAnnouncer = partyAnnouncer;
		this.packSafeModeService = packSafeModeService;
		this.random = random;
	}

	public static boolean isDebugPack(BoosterPackDefinition booster)
	{
		return booster != null && DEBUG_PACK_ID.equals(booster.getId());
	}

	public PackOpenResult buyAndOpenPack(BoosterPackDefinition booster)
	{
		return buyAndOpenPackInternal(booster, false);
	}

	/**
	 * Opens {@code booster} using apex rules (top three display tiers only, 5× foil chance), charging the normal pack price.
	 * Intended for debug ({@code ::tcg-apex}); fails if this booster has no Legendary/Mythic/Godly cards in its pool.
	 */
	public PackOpenResult buyAndOpenApexPackForDebug(BoosterPackDefinition booster)
	{
		return buyAndOpenPackInternal(booster, true);
	}

	private PackOpenResult buyAndOpenPackInternal(BoosterPackDefinition booster, boolean forceApexPack)
	{
		cardDatabase.load();
		long creditsBefore = stateService.getCredits();
		if (booster == null)
		{
			return PackOpenResult.failed("No booster pack selected.", creditsBefore, 0);
		}

		if (packSafeModeService != null && packSafeModeService.isPackOpeningBlocked())
		{
			return PackOpenResult.failed("Cannot open packs while in combat (Safe-mode).", creditsBefore, booster.getPrice());
		}

		boolean debugPack = isDebugPack(booster) && stateService.isDebugLogging();
		if (isDebugPack(booster) && !debugPack)
		{
			return PackOpenResult.failed("Debug pack is only available when debug logging is enabled.", creditsBefore, 0);
		}

		if (forceApexPack && debugPack)
		{
			return PackOpenResult.failed("Apex open is not available for the debug booster.", creditsBefore, 0);
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

		// Rare apex pack (1/3000), or debug forced apex: only Legendary / Mythic / Godly display-tier cards from this pool, 5× foil odds.
		List<CardDefinition> packRollPool = pool;
		boolean apexTopThreeTierOnly = false;
		double foilChanceMultiplier = 1.0d;
		if (!debugPack)
		{
			boolean rollApex = forceApexPack || random.nextInt(APEX_PACK_CHANCE_DENOMINATOR) == 0;
			if (rollApex)
			{
				List<CardDefinition> apexPool = topThreeDisplayTierSubset(pool, rollPool);
				if (!apexPool.isEmpty())
				{
					packRollPool = apexPool;
					apexTopThreeTierOnly = true;
					foilChanceMultiplier = APEX_PACK_FOIL_CHANCE_MULTIPLIER;
				}
				else if (forceApexPack)
				{
					return PackOpenResult.failed(
						"No Legendary, Mythic, or Godly cards in this booster for an apex pack.", creditsBefore, packPrice);
				}
			}
		}

		List<PackCardResult> pulls = debugPack
			? rollDebugSameCardPack(pool)
			: rollPack(packRollPool, rollPool, DEFAULT_PACK_SIZE, apexTopThreeTierOnly, foilChanceMultiplier);
		Map<CardCollectionKey, Integer> ownedBefore;
		synchronized (stateService)
		{
			ownedBefore = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
		}
		if (!stateService.applyPackOpenTransaction(packPrice, pulls, debugPack, localPullerDisplayName()))
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
			booster.getName(), packId, apexTopThreeTierOnly);
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
	private List<PackCardResult> rollPack(List<CardDefinition> regionalPool, List<CardDefinition> globalRollPool, int packSize,
		boolean apexTopThreeTierOnly, double foilChanceMultiplier)
	{
		List<PackCardResult> pulls = new ArrayList<>(packSize);
		int foilPercent = stateService.getState().getRewardTuning().getFoilChancePercent();
		double foilChance = Math.min(1.0d,
			Math.max(0.0d, Math.min(100, foilPercent)) / 100.0d * Math.max(0.0d, foilChanceMultiplier));
		Map<CardDefinition, RarityMath.Tier> globalTierByCard = buildGlobalTierByCard(globalRollPool);
		Map<RarityMath.Tier, List<CardDefinition>> regionalByGlobalTier = partitionRegionalByGlobalTier(regionalPool, globalTierByCard);

		for (int i = 0; i < packSize; i++)
		{
			CardDefinition selected = pickByTierChance(regionalPool, regionalByGlobalTier, apexTopThreeTierOnly);
			boolean foil = random.nextDouble() < foilChance;
			pulls.add(new PackCardResult(selected.getName(), foil));
		}
		if (apexTopThreeTierOnly)
		{
			ensureAtLeastOneFoil(pulls);
		}
		return pulls;
	}

	/** Apex packs always contain at least one foil copy (upgrade a random slot if the roll produced none). */
	private void ensureAtLeastOneFoil(List<PackCardResult> pulls)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return;
		}
		for (PackCardResult p : pulls)
		{
			if (p != null && p.isFoil())
			{
				return;
			}
		}
		int idx = random.nextInt(pulls.size());
		PackCardResult old = pulls.get(idx);
		String name = old == null ? "" : old.getCardName();
		pulls.set(idx, new PackCardResult(name, true));
	}

	/**
	 * Cards in {@code regionalPool} whose display tier (full catalog) is Legendary, Mythic, or Godly.
	 */
	private List<CardDefinition> topThreeDisplayTierSubset(List<CardDefinition> regionalPool, List<CardDefinition> globalRollPool)
	{
		Map<CardDefinition, RarityMath.Tier> globalTierByCard = buildGlobalTierByCard(globalRollPool);
		List<CardDefinition> out = new ArrayList<>();
		for (CardDefinition card : regionalPool)
		{
			if (card == null)
			{
				continue;
			}
			RarityMath.Tier t = globalTierByCard.getOrDefault(card, RarityMath.Tier.COMMON);
			if (t == RarityMath.Tier.LEGENDARY || t == RarityMath.Tier.MYTHIC || t == RarityMath.Tier.GODLY)
			{
				out.add(card);
			}
		}
		return out;
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

	private CardDefinition pickByTierChance(List<CardDefinition> fallbackPool,
		Map<RarityMath.Tier, List<CardDefinition>> regionalByGlobalTier, boolean apexTopThreeTierOnly)
	{
		if (fallbackPool.isEmpty())
		{
			return null;
		}

		for (int attempts = 0; attempts < 8; attempts++)
		{
			RarityMath.Tier tier = apexTopThreeTierOnly ? rollTierApexPackOnly() : rollTier();
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

	/**
	 * Tier roll restricted to Legendary / Mythic / Godly, preserving the same relative odds as the top segment of
	 * {@link #rollTier()} (2% / 2% / 4% of the full bar → 25% / 25% / 50% among the three).
	 */
	private RarityMath.Tier rollTierApexPackOnly()
	{
		double roll = random.nextDouble() * 8.0d;
		if (roll < 2.0d)
		{
			return RarityMath.Tier.GODLY;
		}
		if (roll < 4.0d)
		{
			return RarityMath.Tier.MYTHIC;
		}
		return RarityMath.Tier.LEGENDARY;
	}

	private String localPullerDisplayName()
	{
		if (client == null || client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return "";
		}
		return Text.sanitize(client.getLocalPlayer().getName());
	}
}

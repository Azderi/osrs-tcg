package com.osrstcg.service;

import com.osrstcg.data.CardDefinition;
import com.osrstcg.model.DuplicateKeepTier;
import com.osrstcg.model.DuplicateKeepVersion;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.PullNotifyTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Chooses which duplicate copies to sell, respecting per-instance locks. */
public final class DuplicateSellPlanner
{
	public static final class Result
	{
		private final List<OwnedCardInstance> kept;
		private final long creditsToAdd;
		private final int cardsSold;

		private Result(List<OwnedCardInstance> kept, long creditsToAdd, int cardsSold)
		{
			this.kept = kept;
			this.creditsToAdd = creditsToAdd;
			this.cardsSold = cardsSold;
		}

		public List<OwnedCardInstance> getKept()
		{
			return kept;
		}

		public long getCreditsToAdd()
		{
			return creditsToAdd;
		}

		public int getCardsSold()
		{
			return cardsSold;
		}
	}

	private DuplicateSellPlanner()
	{
	}

	public static boolean hasSellableDuplicates(List<OwnedCardInstance> all, DuplicateKeepVersion keepVersion,
		Function<String, RarityMath.Tier> tierForName, DuplicateKeepTier keepTier)
	{
		return plan(all, name -> null, keepVersion, tierForName, keepTier).getCardsSold() > 0;
	}

	public static Result plan(List<OwnedCardInstance> all, Function<String, CardDefinition> cardDefForName,
		DuplicateKeepVersion keepVersion, Function<String, RarityMath.Tier> tierForName, DuplicateKeepTier keepTier)
	{
		if (all == null || all.isEmpty())
		{
			return new Result(List.of(), 0L, 0);
		}

		Map<String, List<OwnedCardInstance>> byName = new HashMap<>();
		for (OwnedCardInstance i : all)
		{
			if (i == null || i.getCardName() == null)
			{
				continue;
			}
			byName.computeIfAbsent(i.getCardName(), k -> new ArrayList<>()).add(i);
		}

		List<OwnedCardInstance> kept = new ArrayList<>();
		long creditsToAdd = 0L;
		int cardsSold = 0;

		for (Map.Entry<String, List<OwnedCardInstance>> entry : byName.entrySet())
		{
			String name = entry.getKey();
			List<OwnedCardInstance> lst = entry.getValue();
			if (lst.size() <= 1)
			{
				kept.addAll(lst);
				continue;
			}

			RarityMath.Tier tier = tierForName == null ? null : tierForName.apply(name);
			if (keepTier != null && keepTier.toRarityTier() != null && (tier == null || tier.ordinal() >= keepTier.toRarityTier().ordinal()))
			{
				kept.addAll(lst);
				continue;
			}

			CardDefinition def = cardDefForName == null ? null : cardDefForName.apply(name);
			long normalCredits = DuplicateSellCredits.creditsForCard(def, false);
			long foilCredits = DuplicateSellCredits.creditsForCard(def, true);

			List<OwnedCardInstance> locked = new ArrayList<>();
			List<OwnedCardInstance> unlocked = new ArrayList<>();
			for (OwnedCardInstance inst : lst)
			{
				if (inst.isLocked())
				{
					locked.add(inst);
				}
				else
				{
					unlocked.add(inst);
				}
			}
			kept.addAll(locked);

			List<OwnedCardInstance> unlockedFoils = new ArrayList<>();
			List<OwnedCardInstance> unlockedNormals = new ArrayList<>();
			for (OwnedCardInstance inst : unlocked)
			{
				if (inst.isFoil())
				{
					unlockedFoils.add(inst);
				}
				else
				{
					unlockedNormals.add(inst);
				}
			}

			boolean anyFoilInCollection = lst.stream().anyMatch(OwnedCardInstance::isFoil);
			if (anyFoilInCollection)
			{
				if (!unlockedFoils.isEmpty())
				{
					OwnedCardInstance keeper = keeper(unlockedFoils, keepVersion);
					kept.add(keeper);
					for (OwnedCardInstance inst : unlockedFoils)
					{
						if (inst != keeper)
						{
							cardsSold++;
							creditsToAdd += foilCredits;
						}
					}
				}
				for (OwnedCardInstance inst : unlockedNormals)
				{
					cardsSold++;
					creditsToAdd += normalCredits;
				}
			}
			else if (!unlockedNormals.isEmpty())
			{
				OwnedCardInstance keeper = keeper(unlockedNormals, keepVersion);
				kept.add(keeper);
				for (OwnedCardInstance inst : unlockedNormals)
				{
					if (inst != keeper)
					{
						cardsSold++;
						creditsToAdd += normalCredits;
					}
				}
			}
		}

		return new Result(kept, creditsToAdd, cardsSold);
	}

	private static OwnedCardInstance keeper(List<OwnedCardInstance> list, DuplicateKeepVersion keepVersion)
	{
		return keepVersion == DuplicateKeepVersion.OLDEST ? oldest(list) : newest(list);
	}

	private static OwnedCardInstance newest(List<OwnedCardInstance> list)
	{
		return list.stream()
			.max(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs))
			.orElse(list.get(0));
	}
	
	private static OwnedCardInstance oldest(List<OwnedCardInstance> list)
	{
		return list.stream()
			.min(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs))
			.orElse(list.get(0));
	}
}

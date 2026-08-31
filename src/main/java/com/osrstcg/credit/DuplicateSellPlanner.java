package com.osrstcg.credit;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.OwnedCardInstance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Chooses which duplicate copies to sell, respecting per-instance locks and never selling
 * migrated {@code beta} copies.
 */
public final class DuplicateSellPlanner
{
	public static final class Result
	{
		private final List<String> soldInstanceIds;
		private final long creditsToAdd;
		private final int cardsSold;

		private Result(List<String> soldInstanceIds, long creditsToAdd, int cardsSold)
		{
			this.soldInstanceIds = soldInstanceIds;
			this.creditsToAdd = creditsToAdd;
			this.cardsSold = cardsSold;
		}

		/** Non-blank instance IDs planned for sale (never includes locked or beta copies). */
		public List<String> getSoldInstanceIds()
		{
			return soldInstanceIds;
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

	public static boolean hasSellableDuplicates(List<OwnedCardInstance> all)
	{
		return plan(all, name -> null).getCardsSold() > 0;
	}

	public static Result plan(List<OwnedCardInstance> all, Function<String, CardDefinition> cardDefForName)
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

		List<String> soldInstanceIds = new ArrayList<>();
		long creditsToAdd = 0L;
		int cardsSold = 0;

		for (Map.Entry<String, List<OwnedCardInstance>> entry : byName.entrySet())
		{
			String name = entry.getKey();
			List<OwnedCardInstance> lst = entry.getValue();

			List<OwnedCardInstance> protectedCopies = new ArrayList<>();
			List<OwnedCardInstance> sellable = new ArrayList<>();
			for (OwnedCardInstance inst : lst)
			{
				// Locked and migrated beta copies are never sold.
				if (inst.isLocked() || inst.isBeta())
				{
					protectedCopies.add(inst);
				}
				else
				{
					sellable.add(inst);
				}
			}

			if (sellable.isEmpty())
			{
				continue;
			}

			// Only one non-protected copy and nothing else for this name → keep it.
			if (sellable.size() == 1 && protectedCopies.isEmpty())
			{
				continue;
			}

			CardDefinition def = cardDefForName == null ? null : cardDefForName.apply(name);
			long normalCredits = DuplicateSellCredits.creditsForCard(def, false);
			long foilCredits = DuplicateSellCredits.creditsForCard(def, true);

			List<OwnedCardInstance> unlockedFoils = new ArrayList<>();
			List<OwnedCardInstance> unlockedNormals = new ArrayList<>();
			for (OwnedCardInstance inst : sellable)
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

			// Foil presence includes protected (locked/beta) copies - you already have a foil to keep.
			boolean anyFoilInCollection = lst.stream().anyMatch(OwnedCardInstance::isFoil);
			if (anyFoilInCollection)
			{
				if (!unlockedFoils.isEmpty())
				{
					OwnedCardInstance keeper = newest(unlockedFoils);
					for (OwnedCardInstance inst : unlockedFoils)
					{
						if (inst != keeper && markSold(inst, soldInstanceIds))
						{
							cardsSold++;
							creditsToAdd += foilCredits;
						}
					}
				}
				for (OwnedCardInstance inst : unlockedNormals)
				{
					if (markSold(inst, soldInstanceIds))
					{
						cardsSold++;
						creditsToAdd += normalCredits;
					}
				}
			}
			else if (!unlockedNormals.isEmpty())
			{
				OwnedCardInstance keeper = newest(unlockedNormals);
				for (OwnedCardInstance inst : unlockedNormals)
				{
					if (inst != keeper && markSold(inst, soldInstanceIds))
					{
						cardsSold++;
						creditsToAdd += normalCredits;
					}
				}
			}
		}

		return new Result(List.copyOf(soldInstanceIds), creditsToAdd, cardsSold);
	}

	/** Records a sellable instance id; returns false if id is blank (should not happen for cloud cards). */
	private static boolean markSold(OwnedCardInstance inst, List<String> soldInstanceIds)
	{
		if (inst == null || inst.isBeta() || inst.isLocked())
		{
			return false;
		}
		String id = inst.getInstanceId();
		if (id == null || id.isBlank())
		{
			return false;
		}
		soldInstanceIds.add(id.trim());
		return true;
	}

	private static OwnedCardInstance newest(List<OwnedCardInstance> list)
	{
		return list.stream()
			.max(Comparator.comparingLong(OwnedCardInstance::getPulledAtEpochMs))
			.orElse(list.get(0));
	}
}

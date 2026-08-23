package com.osrstcg.ui.collection;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.RarityMath;

public final class CollectionFilterOptions
{
	private CollectionFilterOptions()
	{
	}

	public static final class PackFilterOption
	{
		private final String packId;
		private final String label;

		private PackFilterOption(String packId, String label)
		{
			this.packId = packId;
			this.label = label;
		}

		public static PackFilterOption all()
		{
			return new PackFilterOption(null, "All");
		}

		public static PackFilterOption of(BoosterPackDefinition pack)
		{
			String key = pack.getCollectionKey();
			String collectionName = pack.getCollectionName();
			String label;
			if (collectionName != null && !collectionName.isBlank())
			{
				label = collectionName.trim();
			}
			else
			{
				label = pack.getName() == null || pack.getName().isBlank() ? pack.getId() : pack.getName();
			}
			return new PackFilterOption(key, label == null ? "Pack" : label);
		}

		public String getPackId()
		{
			return packId;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public static final class RarityFilterOption
	{
		private final RarityMath.Tier tier;
		private final String label;

		private RarityFilterOption(RarityMath.Tier tier, String label)
		{
			this.tier = tier;
			this.label = label;
		}

		public static RarityFilterOption all()
		{
			return new RarityFilterOption(null, "All");
		}

		public static RarityFilterOption of(RarityMath.Tier tier)
		{
			return new RarityFilterOption(tier, tier.getLabel());
		}

		public RarityMath.Tier getTier()
		{
			return tier;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}

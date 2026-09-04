package com.osrstcg.ui.collection;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.RarityMath;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
/** Builds the {@link DefaultComboBoxModel} contents (and resolves the current selection) for the collection tab's pack and rarity filter dropdowns. */
public final class CollectionFilterOptions
{
	private CollectionFilterOptions()
	{
	}
/** A populated pack-filter combo model plus the option that should be selected. */
	public static final class PackComboModel
	{
		public final DefaultComboBoxModel<PackFilterOption> model;
		public final PackFilterOption selected;
/** Stores the built model and resolved selection verbatim. */
		PackComboModel(DefaultComboBoxModel<PackFilterOption> model, PackFilterOption selected)
		{
			this.model = model;
			this.selected = selected;
		}
	}
/** A populated rarity-filter combo model plus the option that should be selected. */
	public static final class RarityComboModel
	{
		public final DefaultComboBoxModel<RarityFilterOption> model;
		public final RarityFilterOption selected;
/** Stores the built model and resolved selection verbatim. */
		RarityComboModel(DefaultComboBoxModel<RarityFilterOption> model, RarityFilterOption selected)
		{
			this.model = model;
			this.selected = selected;
		}
	}
/**
	 * Builds a combo model with an "All" option followed by one option per non-null pack, and resolves
	 * which option matches {@code selectedPack} by id.
	 */
	public static PackComboModel packComboModel(List<BoosterPackDefinition> packs, BoosterPackDefinition selectedPack)
	{
		DefaultComboBoxModel<PackFilterOption> model = new DefaultComboBoxModel<>();
		PackFilterOption selected = PackFilterOption.all();
		model.addElement(selected);
		for (BoosterPackDefinition pack : packs)
		{
			if (pack == null)
			{
				continue;
			}
			PackFilterOption option = PackFilterOption.of(pack);
			model.addElement(option);
			if (selectedPack != null && selectedPack.getId() != null
				&& selectedPack.getId().equals(pack.getId()))
			{
				selected = option;
			}
		}
		return new PackComboModel(model, selected);
	}
/**
	 * Builds a combo model with an "All" option followed by one option per {@link RarityMath.Tier},
	 * and resolves which option matches {@code selectedTier}.
	 */
	public static RarityComboModel rarityComboModel(RarityMath.Tier selectedTier)
	{
		DefaultComboBoxModel<RarityFilterOption> model = new DefaultComboBoxModel<>();
		RarityFilterOption selected = RarityFilterOption.all();
		model.addElement(selected);
		for (RarityMath.Tier tier : RarityMath.Tier.values())
		{
			RarityFilterOption option = RarityFilterOption.of(tier);
			model.addElement(option);
			if (selectedTier == tier)
			{
				selected = option;
			}
		}
		return new RarityComboModel(model, selected);
	}
/** A single pack-filter combo entry; a null {@link #packId} represents the "All" option. */
	public static final class PackFilterOption
	{
		private final String packId;
		private final String label;
/** Stores the collection key and display label verbatim. */
		private PackFilterOption(String packId, String label)
		{
			this.packId = packId;
			this.label = label;
		}
/** The "All" (no filter) option. */
		public static PackFilterOption all()
		{
			return new PackFilterOption(null, "All");
		}
/** Builds the option for one pack, preferring its collection name over its own name/id as the label. */
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
/** The pack's collection key, or {@code null} for the "All" option. */
		public String getPackId()
		{
			return packId;
		}
/** The combo box's rendered label. */
		@Override
		public String toString()
		{
			return label;
		}
	}
/** A single rarity-filter combo entry; a null {@link #tier} represents the "All" option. */
	public static final class RarityFilterOption
	{
		private final RarityMath.Tier tier;
		private final String label;
/** Stores the tier and display label verbatim. */
		private RarityFilterOption(RarityMath.Tier tier, String label)
		{
			this.tier = tier;
			this.label = label;
		}
/** The "All" (no filter) option. */
		public static RarityFilterOption all()
		{
			return new RarityFilterOption(null, "All");
		}
/** Builds the option for one rarity tier, using the tier's own label. */
		public static RarityFilterOption of(RarityMath.Tier tier)
		{
			return new RarityFilterOption(tier, tier.getLabel());
		}
/** The filtered rarity tier, or {@code null} for the "All" option. */
		public RarityMath.Tier getTier()
		{
			return tier;
		}
/** The combo box's rendered label. */
		@Override
		public String toString()
		{
			return label;
		}
	}
}

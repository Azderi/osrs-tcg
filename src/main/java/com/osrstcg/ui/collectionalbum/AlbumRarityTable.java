package com.runelitetcg.ui.collectionalbum;

import com.runelitetcg.data.CardDefinition;
import com.runelitetcg.service.RarityMath;
import com.runelitetcg.ui.SharedCardRenderer;
import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rarity colors for the collection album; tiers come from {@link RarityMath#displayTierByCardName(List)} (same as pack reveal).
 */
public final class AlbumRarityTable
{
	private final Map<String, Color> colorByCardName;

	private AlbumRarityTable(Map<String, Color> colorByCardName)
	{
		this.colorByCardName = colorByCardName;
	}

	public static AlbumRarityTable build(List<CardDefinition> allCards)
	{
		Map<String, Color> map = new HashMap<>();
		if (allCards == null || allCards.isEmpty())
		{
			return new AlbumRarityTable(map);
		}

		Map<String, RarityMath.Tier> tierByName = RarityMath.displayTierByCardName(allCards);
		for (CardDefinition card : allCards)
		{
			if (card == null || card.getName() == null || card.getName().trim().isEmpty())
			{
				continue;
			}
			RarityMath.Tier tier = tierByName.getOrDefault(card.getName(), RarityMath.Tier.COMMON);
			map.put(card.getName(), tier.getColor());
		}
		return new AlbumRarityTable(map);
	}

	public Color colorForCardName(String cardName)
	{
		if (cardName == null)
		{
			return Color.WHITE;
		}
		return colorByCardName.getOrDefault(cardName, Color.WHITE);
	}

	public String tierLabelForCard(CardDefinition card)
	{
		if (card == null || card.getName() == null)
		{
			return "Common";
		}
		return SharedCardRenderer.tierLabelForRarityColor(colorForCardName(card.getName()));
	}
}

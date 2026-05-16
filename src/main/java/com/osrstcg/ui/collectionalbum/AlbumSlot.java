package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDefinition;
import java.awt.Color;

public final class AlbumSlot
{
	private final CardDefinition card;
	private final Color rarityColor;
	private final boolean ownedAny;
	private final boolean displayFoil;
	private final int nonFoilQty;
	private final int foilQty;
	/** Non-null only when exactly one copy is owned; shown as album hover text. */
	private final String singleCopyHoverTooltip;

	public AlbumSlot(CardDefinition card, Color rarityColor, boolean ownedAny, boolean displayFoil,
		int nonFoilQty, int foilQty, String singleCopyHoverTooltip)
	{
		this.card = card;
		this.rarityColor = rarityColor;
		this.ownedAny = ownedAny;
		this.displayFoil = displayFoil;
		this.nonFoilQty = Math.max(0, nonFoilQty);
		this.foilQty = Math.max(0, foilQty);
		this.singleCopyHoverTooltip = singleCopyHoverTooltip == null || singleCopyHoverTooltip.isEmpty()
			? null
			: singleCopyHoverTooltip;
	}

	public CardDefinition card()
	{
		return card;
	}

	public Color rarityColor()
	{
		return rarityColor;
	}

	public boolean ownedAny()
	{
		return ownedAny;
	}

	public boolean displayFoil()
	{
		return displayFoil;
	}

	public int nonFoilQty()
	{
		return nonFoilQty;
	}

	public int foilQty()
	{
		return foilQty;
	}

	public int totalOwnedQty()
	{
		return nonFoilQty + foilQty;
	}

	public String singleCopyHoverTooltip()
	{
		return singleCopyHoverTooltip;
	}
}

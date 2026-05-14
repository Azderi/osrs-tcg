package com.runelitetcg.model;

import java.util.Objects;

public final class CardCollectionKey
{
	private final String cardName;
	private final boolean foil;

	public CardCollectionKey(String cardName, boolean foil)
	{
		this.cardName = cardName == null ? "" : cardName;
		this.foil = foil;
	}

	public String getCardName()
	{
		return cardName;
	}

	public boolean isFoil()
	{
		return foil;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof CardCollectionKey))
		{
			return false;
		}
		CardCollectionKey that = (CardCollectionKey) o;
		return foil == that.foil && Objects.equals(cardName, that.cardName);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(cardName, foil);
	}
}

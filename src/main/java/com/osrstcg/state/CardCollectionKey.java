package com.osrstcg.state;

import java.util.Objects;

/**
 * Identity key for aggregating owned card counts: card name plus foil flag. Used as the map key
 * in {@link CollectionState#getOwnedCards()}.
 */
public final class CardCollectionKey
{
	private final String cardName;
	private final boolean foil;

	/** Normalizes a null card name to empty string. */
	public CardCollectionKey(String cardName, boolean foil)
	{
		this.cardName = cardName == null ? "" : cardName;
		this.foil = foil;
	}

	/** Returns the card name (never null). */
	public String getCardName()
	{
		return cardName;
	}

	/** Returns whether this key represents the foil variant of the card. */
	public boolean isFoil()
	{
		return foil;
	}

	/** Equal when card name and foil flag both match. */
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

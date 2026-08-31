package com.osrstcg.ui.card;

import com.osrstcg.catalog.CardDefinition;
import java.awt.Color;
import java.awt.image.BufferedImage;
import lombok.Getter;

@Getter
public final class CardFaceDrawRequest
{
	private final CardDefinition card;
	private final BufferedImage art;
	private final String artKey;
	private final boolean foil;
	private final Color rarityColor;
	private final String tierLabel;
	private final Long displayScore;
	private final boolean useFoilAdjustedScore;
	private final WearFx wear;
	private final FoilFx foilFx;

	private CardFaceDrawRequest(Builder b)
	{
		this.card = b.card;
		this.art = b.art;
		this.artKey = b.artKey == null || b.artKey.isBlank() ? null : b.artKey.trim();
		this.foil = b.foil;
		this.rarityColor = b.rarityColor == null ? Color.WHITE : b.rarityColor;
		this.tierLabel = b.tierLabel;
		this.displayScore = b.displayScore;
		this.useFoilAdjustedScore = b.useFoilAdjustedScore == null ? b.foil : b.useFoilAdjustedScore;
		this.wear = b.wear;
		this.foilFx = (!b.foil) ? null : b.foilFx;
	}

	public boolean isFullArt()
	{
		if (!foil || card == null)
		{
			return false;
		}
		String path = card.getFoilImagePath();
		return path != null && !path.isBlank();
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public static final class Builder
	{
		private CardDefinition card;
		private BufferedImage art;
		private String artKey;
		private boolean foil;
		private Color rarityColor = Color.WHITE;
		private String tierLabel;
		private Long displayScore;
		private Boolean useFoilAdjustedScore;
		private WearFx wear;
		private FoilFx foilFx;

		public Builder card(CardDefinition value)
		{
			this.card = value;
			return this;
		}

		public Builder art(BufferedImage value)
		{
			this.art = value;
			return this;
		}

		public Builder artKey(String value)
		{
			this.artKey = value;
			return this;
		}

		public Builder foil(boolean value)
		{
			this.foil = value;
			return this;
		}

		public Builder rarityColor(Color value)
		{
			this.rarityColor = value;
			return this;
		}

		public Builder tierLabel(String value)
		{
			this.tierLabel = value;
			return this;
		}

		public Builder displayScore(Long value)
		{
			this.displayScore = value;
			return this;
		}

		public Builder useFoilAdjustedScore(boolean value)
		{
			this.useFoilAdjustedScore = value;
			return this;
		}

		public Builder wear(WearFx value)
		{
			this.wear = value;
			return this;
		}

		public Builder foilFx(FoilFx value)
		{
			this.foilFx = value;
			return this;
		}

		public CardFaceDrawRequest build()
		{
			return new CardFaceDrawRequest(this);
		}
	}
}

package com.osrstcg.ui.card;

import com.osrstcg.catalog.CardDefinition;
import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Everything {@code SharedCardRenderer.drawCardFace} needs for one card face, matching the
 * website inspect / album face. Build with {@link #builder()}; all fields are optional except the card.
 */
public final class CardFaceDrawRequest
{
	private final CardDefinition card;
	private final BufferedImage art;
	/** Stable art identity (URL/path) for face-cache keys; falls back to image identity when null. */
	private final String artKey;
	private final boolean foil;
	private final Color rarityColor;
	private final String tierLabel;
	private final Long displayScore;
	private final boolean useFoilAdjustedScore;
	private final WearFx wear;
	private final FoilFx foilFx;
	private final boolean drawFoilOverlays;
	private final boolean locked;
	private final boolean beta;

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
		this.drawFoilOverlays = b.drawFoilOverlays;
		this.locked = b.locked;
		this.beta = b.beta;
		this.foilFx = (!b.foil || !b.drawFoilOverlays)
			? null
			: (b.foilFx != null
				? b.foilFx
				: FoilFx.foilFxFromPulledAt(
					b.pulledAtEpochMs,
					FoilFx.DEFAULT_SPARKLE_COUNT,
					b.card == null ? "" : b.card.getName(),
					b.tierLabel,
					b.rarityColor));
	}

	public CardDefinition getCard()
	{
		return card;
	}

	public BufferedImage getArt()
	{
		return art;
	}

	public String getArtKey()
	{
		return artKey;
	}

	public boolean isFoil()
	{
		return foil;
	}

	/**
	 * Foil instance with a non-empty catalog {@code foilImagePath} - full-bleed art layout
	 * (website {@code AlbumCardFace}).
	 */
	public boolean isFullArt()
	{
		if (!foil || card == null)
		{
			return false;
		}
		String path = card.getFoilImagePath();
		return path != null && !path.isBlank();
	}

	public Color getRarityColor()
	{
		return rarityColor;
	}

	/** Catalog tier label; when null the renderer derives one from {@link #getRarityColor()}. */
	public String getTierLabel()
	{
		return tierLabel;
	}

	/** Server-provided score; when null the renderer computes it from the card definition. */
	public Long getDisplayScore()
	{
		return displayScore;
	}

	public boolean isUseFoilAdjustedScore()
	{
		return useFoilAdjustedScore;
	}

	public WearFx getWear()
	{
		return wear;
	}

	public FoilFx getFoilFx()
	{
		return foilFx;
	}

	public boolean isDrawFoilOverlays()
	{
		return drawFoilOverlays;
	}

	public boolean isLocked()
	{
		return locked;
	}

	public boolean isBeta()
	{
		return beta;
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
		private Long pulledAtEpochMs;
		private boolean drawFoilOverlays = true;
		private boolean locked;
		private boolean beta;

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

		/** Seeds {@link FoilFx} when one was not supplied directly. */
		public Builder pulledAtEpochMs(Long value)
		{
			this.pulledAtEpochMs = value;
			return this;
		}

		public Builder drawFoilOverlays(boolean value)
		{
			this.drawFoilOverlays = value;
			return this;
		}

		public Builder locked(boolean value)
		{
			this.locked = value;
			return this;
		}

		public Builder beta(boolean value)
		{
			this.beta = value;
			return this;
		}

		public CardFaceDrawRequest build()
		{
			return new CardFaceDrawRequest(this);
		}
	}
}

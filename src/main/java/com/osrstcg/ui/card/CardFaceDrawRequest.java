package com.osrstcg.ui.card;

import com.osrstcg.catalog.CardDefinition;
import java.awt.Color;
import java.awt.image.BufferedImage;
import lombok.Getter;
/**
 * Immutable, builder-constructed bundle of everything needed to render one card face: art, rarity color,
 * labels/score, and the optional foil/wear effect state.
 */
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
/** Copies and normalizes builder fields: blanks artKey to null, defaults rarityColor to white, and drops foilFx when not foil. */
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
/** True when this is a foil card with a full-art foil image path defined on {@link #card}. */
	public boolean isFullArt()
	{
		if (!foil || card == null)
		{
			return false;
		}
		String path = card.getFoilImagePath();
		return path != null && !path.isBlank();
	}
/** Starts a new {@link Builder}. */
	public static Builder builder()
	{
		return new Builder();
	}
/** Fluent builder for {@link CardFaceDrawRequest}; every setter returns {@code this}. */
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
/** Sets the card definition being rendered. */
		public Builder card(CardDefinition value)
		{
			this.card = value;
			return this;
		}
/** Sets the base card art image. */
		public Builder art(BufferedImage value)
		{
			this.art = value;
			return this;
		}
/** Sets the cache key identifying this art variant (trimmed and null-if-blank on build). */
		public Builder artKey(String value)
		{
			this.artKey = value;
			return this;
		}
/** Sets whether this card is foil. */
		public Builder foil(boolean value)
		{
			this.foil = value;
			return this;
		}
/** Sets the rarity tier color (defaults to white if never set or set null). */
		public Builder rarityColor(Color value)
		{
			this.rarityColor = value;
			return this;
		}
/** Sets the tier label text. */
		public Builder tierLabel(String value)
		{
			this.tierLabel = value;
			return this;
		}
/** Sets the score value to display. */
		public Builder displayScore(Long value)
		{
			this.displayScore = value;
			return this;
		}
/** Sets whether the displayed score is foil-adjusted; unset defaults to the {@link #foil} flag. */
		public Builder useFoilAdjustedScore(boolean value)
		{
			this.useFoilAdjustedScore = value;
			return this;
		}
/** Sets the wear (condition damage) effect to render. */
		public Builder wear(WearFx value)
		{
			this.wear = value;
			return this;
		}
/** Sets the foil sparkle effect to render (ignored on build unless {@link #foil} is set true). */
		public Builder foilFx(FoilFx value)
		{
			this.foilFx = value;
			return this;
		}
/** Builds the immutable {@link CardFaceDrawRequest} from the accumulated fields. */
		public CardFaceDrawRequest build()
		{
			return new CardFaceDrawRequest(this);
		}
	}
}

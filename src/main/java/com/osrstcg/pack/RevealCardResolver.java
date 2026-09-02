package com.osrstcg.pack;

import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.util.CardDisplayNames;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves server pack pulls into overlay {@link PackRevealService.RevealCard}s (placeholders,
 * catalog materialization, rarity index). Callers must hold the reveal-service lock.
 */
@Slf4j
final class RevealCardResolver
{
	private final CardDatabase cardDatabase;
	private final Map<String, RarityMath.Tier> rarityTierByCardName = new HashMap<>();

	RevealCardResolver(CardDatabase cardDatabase)
	{
		this.cardDatabase = cardDatabase;
	}

	/** Rebuilds the card-name-to-rarity-tier lookup from the current catalog; call before resolving a reveal. */
	void rebuildRarityTierIndex()
	{
		rarityTierByCardName.clear();
		for (CardDefinition card : cardDatabase.getCards())
		{
			if (card == null || card.getName() == null || card.getName().trim().isEmpty())
			{
				continue;
			}
			RarityMath.Tier tier = RarityMath.tierFromLabel(card.getTierLabel());
			rarityTierByCardName.put(card.getName().toLowerCase(), tier);
		}
	}

	/** Builds {@code count} face-down common placeholder cards shown while server pulls are pending. */
	List<PackRevealService.RevealCard> createPlaceholderCards(int count)
	{
		if (count <= 0)
		{
			return List.of();
		}
		List<PackRevealService.RevealCard> out = new ArrayList<>(count);
		Color commonColor = RarityMath.Tier.COMMON.getColor();
		for (int i = 0; i < count; i++)
		{
			out.add(new PackRevealService.RevealCard(
				new PackCardResult("", false),
				null,
				RarityMath.Tier.COMMON,
				commonColor,
				false));
		}
		return List.copyOf(out);
	}

	/**
	 * Converts server pack pulls into {@link PackRevealService.RevealCard}s: resolves each pull against the
	 * card catalog, materializes a display definition, resolves rarity tier, and flags cards not already
	 * present in {@code preOwnedCards} as new. Pulls with a null card name are dropped.
	 */
	List<PackRevealService.RevealCard> resolveRevealCards(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards)
	{
		if (pulls == null || pulls.isEmpty())
		{
			return List.of();
		}
		List<PackRevealService.RevealCard> resolved = new ArrayList<>();
		Set<String> preOwnedKeys = preOwnedCards == null ? Set.of() : preOwnedCards.stream()
			.filter(Objects::nonNull)
			.map(k -> normalizeKey(k.getCardName(), k.isFoil()))
			.collect(Collectors.toSet());
		for (PackCardResult pull : pulls)
		{
			if (pull == null || pull.getCardName() == null)
			{
				continue;
			}

			CardDefinition catalog = findCard(pull.getCardName()).orElse(null);
			CardDefinition definition = materializeRevealDefinition(pull, catalog);
			RarityMath.Tier tier = tierForPackPull(pull, pull.getCardName());
			Color rarityColor = tier.getColor();
			boolean isNew = !preOwnedKeys.contains(normalizeKey(pull.getCardName(), pull.isFoil()));
			resolved.add(new PackRevealService.RevealCard(pull, definition, tier, rarityColor, isNew));
		}
		return resolved;
	}

	/**
	 * Prefer server {@code tierLabel} for cloud pack pulls; otherwise local catalog display tier.
	 */
	RarityMath.Tier tierForPackPull(PackCardResult pull, String catalogCardName)
	{
		if (pull != null && pull.hasServerTier())
		{
			Optional<RarityMath.Tier> parsed = RarityMath.tryParseTierLabel(pull.getTierLabel());
			if (parsed.isEmpty())
			{
				log.warn("Unknown pack pull tierLabel '{}' for card '{}' - using Common",
					pull.getTierLabel(), pull.getCardName());
				return RarityMath.Tier.COMMON;
			}
			return parsed.get();
		}
		return tierForCard(catalogCardName);
	}

	/**
	 * Builds the {@link CardDefinition} shown for a pull: starts from the local catalog entry when found
	 * (falling back to a minimal definition otherwise), then overlays server-supplied image paths, artist
	 * credit, examine text, wiki page, and score/tier for pulls carrying server data.
	 */
	private CardDefinition materializeRevealDefinition(PackCardResult pull, CardDefinition catalog)
	{
		CardDefinition definition = new CardDefinition();
		String title = CardDisplayNames.titleForPull(pull, catalog);
		if (catalog != null)
		{
			definition.setName(title);
			definition.setDisplayName(CardDisplayNames.firstNonBlank(
				pull != null ? pull.getDisplayName() : null,
				catalog.getDisplayName(),
				catalog.getName()));
			definition.setId(catalog.getId());
			definition.setCategory(catalog.getCategory() == null ? new ArrayList<>() : new ArrayList<>(catalog.getCategory()));
			definition.setRegions(catalog.getRegions() == null ? new ArrayList<>() : new ArrayList<>(catalog.getRegions()));
			definition.setImageUrl(catalog.getImageUrl());
			definition.setLevel(catalog.getLevel());
			definition.setValue(catalog.getValue());
			definition.setScore(catalog.getScore());
			definition.setFoilScore(catalog.getFoilScore());
			definition.setTierLabel(catalog.getTierLabel());
			definition.setExamine(catalog.getExamine());
			definition.setWikiPage(catalog.getWikiPage());
		}
		else
		{
			definition.setName(title);
			definition.setDisplayName(pull != null ? pull.getDisplayName() : null);
			definition.setCategory(new ArrayList<>());
			definition.setExamine("No examine text.");
		}

		if (pull.getImagePath() != null && !pull.getImagePath().isBlank())
		{
			String absolute = CloudEndpoints.resolvePublicUrl(pull.getImagePath());
			if (absolute != null && !absolute.isBlank())
			{
				definition.setImageUrl(absolute);
			}
		}
		if (pull.getFoilImagePath() != null && !pull.getFoilImagePath().isBlank())
		{
			String absolute = CloudEndpoints.resolvePublicUrl(pull.getFoilImagePath());
			if (absolute != null && !absolute.isBlank())
			{
				definition.setFoilImagePath(absolute);
			}
			if (pull.getArtistName() != null && !pull.getArtistName().isBlank())
			{
				definition.setArtistName(pull.getArtistName());
			}
			if (pull.getArtistColor() != null && !pull.getArtistColor().isBlank())
			{
				definition.setArtistColor(pull.getArtistColor());
			}
			if (pull.getArtistUrl() != null && !pull.getArtistUrl().isBlank())
			{
				definition.setArtistUrl(pull.getArtistUrl());
			}
			if (pull.getExamine() != null && !pull.getExamine().isBlank())
			{
				definition.setExamine(pull.getExamine());
			}
		}
		if (pull.getWikiPage() != null && !pull.getWikiPage().isBlank())
		{
			definition.setWikiPage(pull.getWikiPage());
		}

		if (pull.hasServerTier() || pull.getScore() > 0L)
		{
			if (pull.isFoil())
			{
				definition.setFoilScore(pull.getScore());
			}
			else
			{
				definition.setScore(pull.getScore());
			}
			if (pull.getTierLabel() != null && !pull.getTierLabel().isBlank())
			{
				definition.setTierLabel(pull.getTierLabel());
			}
			definition.setValue(null);
		}
		return definition;
	}

	/** Case-insensitive lookup of a catalog card by name. */
	private Optional<CardDefinition> findCard(String name)
	{
		return cardDatabase.getCards().stream()
			.filter(Objects::nonNull)
			.filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(name))
			.findFirst();
	}

	/** Looks up the local catalog display tier for a card name, defaulting to {@code COMMON} when unknown. */
	private RarityMath.Tier tierForCard(String cardName)
	{
		if (cardName == null)
		{
			return RarityMath.Tier.COMMON;
		}
		return rarityTierByCardName.getOrDefault(cardName.toLowerCase(), RarityMath.Tier.COMMON);
	}

	/** Builds a case-insensitive, foil-aware dedup key for pre-owned-card comparisons. */
	private static String normalizeKey(String cardName, boolean foil)
	{
		return (cardName == null ? "" : cardName.trim().toLowerCase()) + "|" + (foil ? "1" : "0");
	}
}

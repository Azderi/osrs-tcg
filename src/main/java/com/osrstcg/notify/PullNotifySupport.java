package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.config.PullNotificationTrigger;
import com.osrstcg.config.PullNotifyTier;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.pack.PackRevealService.RevealCard;
import com.osrstcg.state.TcgPublicStats;
import com.osrstcg.ui.card.CardGrade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
/**
 * Shared logic for building notification content and deciding eligibility, used by chat, party,
 * webhook, and Dink notifiers so they stay in sync on filtering rules and message text.
 */
@Singleton
public class PullNotifySupport
{
/** Pre-built end-of-pack summary: sections, thumbnail image, rarity tier, and per-card structured metadata. */
	public static final class PackSummaryContent
	{
		public final PullNotificationMessages.PackSummarySections sections;
		public final String imageUrl;
		public final RarityMath.Tier tier;
		public final List<Map<String, Object>> newCardDetails;
		public final List<Map<String, Object>> duplicateDetails;
/** Stores the summary sections, thumbnail image URL, tier, and per-card structured metadata verbatim. */
		PackSummaryContent(
			PullNotificationMessages.PackSummarySections sections, String imageUrl, RarityMath.Tier tier,
			List<Map<String, Object>> newCardDetails, List<Map<String, Object>> duplicateDetails)
		{
			this.sections = sections;
			this.imageUrl = imageUrl;
			this.tier = tier;
			this.newCardDetails = newCardDetails;
			this.duplicateDetails = duplicateDetails;
		}
/** Renders the summary message with the given opener name substituted in. */
		public String messageFor(String opener)
		{
			return PullNotificationMessages.packSummaryMessage(opener, sections);
		}
	}
/** Pre-built per-card notification content: message text, card image URL, inspect link, catalog tags, and grade. */
	public static final class PullCardContent
	{
		public final String description;
		public final String imageUrl;
		public final String inspectUrl;
		public final List<String> category;
		public final List<String> regions;
		public final Double condition;
		public final String conditionGrade;
/** Stores the description, image URL, inspect URL, catalog tags, and graded condition verbatim. */
		PullCardContent(
			String description, String imageUrl, String inspectUrl, List<String> category, List<String> regions,
			Double condition, String conditionGrade)
		{
			this.description = description;
			this.imageUrl = imageUrl;
			this.inspectUrl = inspectUrl;
			this.category = category;
			this.regions = regions;
			this.condition = condition;
			this.conditionGrade = conditionGrade;
		}
	}

	private final OsrsTcgConfig config;
	private final CardDatabase cardDatabase;
	private final TcgPublicStatsCalculator tcgPublicStatsCalculator;
	private final TcgChatStatsShareService tcgChatStatsShareService;
/** Wires config, the card database, and the public-stats calculator/share service used to build stats lines. */
	@Inject
	PullNotifySupport(
		OsrsTcgConfig config,
		CardDatabase cardDatabase,
		TcgPublicStatsCalculator tcgPublicStatsCalculator,
		TcgChatStatsShareService tcgChatStatsShareService)
	{
		this.config = config;
		this.cardDatabase = cardDatabase;
		this.tcgPublicStatsCalculator = tcgPublicStatsCalculator;
		this.tcgChatStatsShareService = tcgChatStatsShareService;
	}
/**
	 * Decides whether a pull is eligible for external notification (webhook/Dink), applying the
	 * new-cards-only, foil, non-foil, and per-category tier-floor config settings.
	 */
	public boolean shouldNotify(RarityMath.Tier tier, boolean foil, boolean newForCollection)
	{
		PullNotifyTier floor = newForCollection ? config.notifyTier() : config.duplicateNotifyTier();
		if (config.notifyNewCardsOnly() && !newForCollection && !(foil && config.notifyFoils()))
		{
			return false;
		}
		if (foil)
		{
			if (tier == null)
			{
				return config.notifyFoils();
			}
			return meetsTier(tier, floor) || config.notifyFoils();
		}
		if (tier == null || !config.notifyNonFoils())
		{
			return false;
		}
		return meetsTier(tier, floor);
	}
/** Returns the configured notification trigger, defaulting to per-card when unset. */
	public PullNotificationTrigger notificationTrigger()
	{
		PullNotificationTrigger trigger = config.pullNotificationTrigger();
		return trigger == null ? PullNotificationTrigger.EVERY_CARD : trigger;
	}
/** Converts reveal-service cards into {@link PullNotificationMessages.PackPull}s, computing notify-eligibility for each. */
	public List<PullNotificationMessages.PackPull> packPullsFromCards(List<RevealCard> cards)
	{
		List<PullNotificationMessages.PackPull> pulls = new ArrayList<>();
		if (cards == null)
		{
			return pulls;
		}
		for (RevealCard card : cards)
		{
			if (card == null || card.getPull() == null || card.getPull().getCardName() == null)
			{
				continue;
			}
			pulls.add(new PullNotificationMessages.PackPull(
				card.getPull().getCardName().trim(),
				card.isNew(),
				card.getPull().isFoil(),
				card.getTier(),
				card.getPull().getInstanceId(),
				shouldNotify(card.getTier(), card.getPull().isFoil(), card.isNew()),
				card.getPull().getCondition(),
				card.getPull().getScore(),
				card.getPull().getPulledAtEpochMs()));
		}
		return pulls;
	}
/**
	 * Builds the end-of-pack summary content, or empty if no pull is notification-eligible or both
	 * summary sections end up empty.
	 */
	public Optional<PackSummaryContent> packSummaryContent(List<PullNotificationMessages.PackPull> pulls)
	{
		if (!PullNotificationMessages.hasEligiblePull(pulls))
		{
			return Optional.empty();
		}
		PullNotificationMessages.PackSummarySections sections = PullNotificationMessages.buildSummarySections(
			pulls, config.showPullGradeAndCondition());
		if (sections.newCards.isEmpty() && sections.duplicates.isEmpty())
		{
			return Optional.empty();
		}
		PullNotificationMessages.PackPull thumbnailPull = PullNotificationMessages.highestTierPull(pulls);
		String imageUrl = thumbnailPull == null ? "" : cardImageUrl(thumbnailPull.cardName);
		RarityMath.Tier tier = thumbnailPull == null ? null : thumbnailPull.tier;
		List<Map<String, Object>> newCardDetails = new ArrayList<>();
		List<Map<String, Object>> duplicateDetails = new ArrayList<>();
		for (PullNotificationMessages.PackPull pull : PullNotificationMessages.sortedForSummary(pulls))
		{
			if (pull == null || pull.cardName == null || pull.cardName.trim().isEmpty())
			{
				continue;
			}
			(pull.newForCollection ? newCardDetails : duplicateDetails).add(pullDetail(pull));
		}
		return Optional.of(new PackSummaryContent(sections, imageUrl, tier, newCardDetails, duplicateDetails));
	}
/** Builds one card's structured metadata (name, foil/tier/score, links, category/regions, grade, pull time). */
	private Map<String, Object> pullDetail(PullNotificationMessages.PackPull pull)
	{
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("cardName", pull.cardName.trim());
		detail.put("foil", pull.foil);
		detail.put("rarityTier", pull.tier == null ? "" : pull.tier.getLabel());
		detail.put("score", pull.score);
		String inspectUrl = PullNotificationMessages.inspectUrl(pull.instanceId);
		if (pull.instanceId != null && !pull.instanceId.isBlank())
		{
			detail.put("instanceId", pull.instanceId.trim());
		}
		if (!inspectUrl.isEmpty())
		{
			detail.put("inspectUrl", inspectUrl);
		}
		CardDefinition definition = cardDatabase.findByName(pull.cardName).orElse(null);
		String imageUrl = imageUrlForDefinition(definition);
		if (!imageUrl.isEmpty())
		{
			detail.put("imageUrl", imageUrl);
		}
		detail.put("category", PullNotificationMessages.categoryTagsOrEmpty(definition));
		detail.put("regions", PullNotificationMessages.regionTagsOrEmpty(definition));
		Double gradedCondition = gradedCondition(pull.condition);
		if (gradedCondition != null)
		{
			detail.put("condition", gradedCondition);
			detail.put("conditionGrade", conditionGradeLabel(gradedCondition));
		}
		String pulledAt = PullNotificationMessages.pulledAtIso(pull.pulledAtEpochMs);
		if (pulledAt != null)
		{
			detail.put("pulledAt", pulledAt);
		}
		return detail;
	}
/** Builds the message text, card image URL, and inspect URL for a single-card notification. */
	public PullCardContent pullCardContent(
		String cardName, boolean newForCollection, boolean foil, String instanceId, String opener,
		Double condition)
	{
		String trimmed = cardName.trim();
		String inspectUrl = PullNotificationMessages.inspectUrl(instanceId);
		CardDefinition definition = cardDatabase.findByName(trimmed).orElse(null);
		Double gradedCondition = gradedCondition(condition);
		return new PullCardContent(
			PullNotificationMessages.collectionMessage(
				opener, trimmed, newForCollection, foil, inspectUrl, gradedCondition),
			imageUrlForDefinition(definition),
			inspectUrl,
			PullNotificationMessages.categoryTagsOrEmpty(definition),
			PullNotificationMessages.regionTagsOrEmpty(definition),
			gradedCondition,
			conditionGradeLabel(gradedCondition));
	}
/** Applies the grade-display config gate: returns {@code condition} when grading is on and it's a finite value, else null. */
	private Double gradedCondition(Double condition)
	{
		if (!config.showPullGradeAndCondition() || condition == null || condition.isNaN() || condition.isInfinite())
		{
			return null;
		}
		return condition;
	}
/** Letter grade (S-E) for an already-gated condition value, or null if there is none. */
	private static String conditionGradeLabel(Double gradedCondition)
	{
		return gradedCondition == null ? null : CardGrade.gradeFromCondition(gradedCondition).name();
	}
/** Resolves a card's public image URL (as .webp), or "" if the card is unknown or has no image. */
	public String cardImageUrl(String cardName)
	{
		return imageUrlForDefinition(cardDatabase.findByName(cardName).orElse(null));
	}
/** Resolves a card definition's public image URL (as .webp), or "" if null or the definition has no image. */
	private static String imageUrlForDefinition(CardDefinition definition)
	{
		if (definition == null || definition.getImageUrl() == null || definition.getImageUrl().isEmpty())
		{
			return "";
		}
		return toWebpUrl(CloudEndpoints.resolvePublicUrl(definition.getImageUrl()));
	}
/** Rewrites a ".png" image URL to ".webp"; passes other URLs through unchanged. */
	private static String toWebpUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return "";
		}
		return url.endsWith(".png") ? url.substring(0, url.length() - 4) + ".webp" : url;
	}
/** Renders the plain-text public collection stats line shown on external notifications. */
	public String statsPlainLine()
	{
		return statsPlainLine(currentStats());
	}
/** Renders {@code stats} as the same plain-text summary line as {@link #statsPlainLine()}. */
	public String statsPlainLine(TcgPublicStats stats)
	{
		return tcgChatStatsShareService.buildPlainLine(stats);
	}
/** Appends the public stats line to a notification message, separated by a blank line. */
	public String messageWithStatsLine(String message)
	{
		return messageWithStatsLine(message, currentStats());
	}
/** Appends {@code stats}' plain-text summary line to a notification message, separated by a blank line. */
	public String messageWithStatsLine(String message, TcgPublicStats stats)
	{
		return message + "\n\n" + statsPlainLine(stats);
	}
/** Computes a fresh collection-stats snapshot (state + catalog at the current moment). */
	public TcgPublicStats currentStats()
	{
		return tcgPublicStatsCalculator.computeLive();
	}
/** {@code stats} as a structured map (score, completion, unique/foil counts, pool size, packs opened), for JSON consumers like Dink. */
	public static Map<String, Object> collectionStatsSummary(TcgPublicStats stats)
	{
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("collectionScore", stats.getCollectionScore());
		summary.put("completionPct", stats.getCompletionPct());
		summary.put("uniqueOwned", stats.getUniqueOwned());
		summary.put("uniqueFoilOwned", stats.getUniqueFoilOwned());
		summary.put("foilCompletionPct", stats.getFoilCompletionPct());
		summary.put("totalCardPool", stats.getTotalCardPool());
		summary.put("openedPacks", stats.getOpenedPacks());
		summary.put("totalCardsOwned", stats.getTotalCardsOwned());
		summary.put("foilOwned", stats.getFoilOwned());
		return summary;
	}
/** True if {@code tier} meets or exceeds {@code floor} (defaulting to MYTHIC, the strictest, when floor is unset). */
	private static boolean meetsTier(RarityMath.Tier tier, PullNotifyTier floor)
	{
		if (tier == null)
		{
			return false;
		}
		PullNotifyTier minimum = floor == null ? PullNotifyTier.MYTHIC : floor;
		return minimum.meetsOrExceeds(tier);
	}
}

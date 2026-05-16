package com.osrstcg.service;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.PackCardResult;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;

@Singleton
public class PackRevealService
{
	public enum Phase
	{
		IDLE,
		PACK_READY,
		PACK_FADING,
		/** Cards fly from a central pile into the 2+3 grid. */
		CARD_DEAL,
		CARD_REVEAL,
		WAIT_CLOSE
	}

	@Value
	public static class RevealCard
	{
		PackCardResult pull;
		CardDefinition definition;
		RarityMath.Tier tier;
		Color rarityColor;
		long basePullDenominator;
		boolean isNew;
	}

	/**
	 * Immutable snapshot of reveal state for one overlay paint. The overlay must not mix a captured card list with
	 * live {@link #isCardRevealed(int)} calls across threads: after {@link #reset()}, the old list can still be
	 * referenced while reveal flags are cleared, which briefly paints every slot face-down.
	 */
	public static final class RevealPaintSnapshot
	{
		private final Phase phase;
		private final List<RevealCard> cards;
		private final boolean[] revealedByIndex;
		private final long phaseElapsedMs;
		private final double packFadeProgress;
		private final String boosterPackId;
		private final boolean showScrollWheelOverlayHint;
		private final boolean apexPackOpen;

		private RevealPaintSnapshot(Phase phase, List<RevealCard> cards, boolean[] revealedByIndex, long phaseElapsedMs,
			double packFadeProgress, String boosterPackId, boolean showScrollWheelOverlayHint, boolean apexPackOpen)
		{
			this.phase = phase;
			this.cards = cards;
			this.revealedByIndex = revealedByIndex;
			this.phaseElapsedMs = phaseElapsedMs;
			this.packFadeProgress = packFadeProgress;
			this.boosterPackId = boosterPackId == null ? "" : boosterPackId;
			this.showScrollWheelOverlayHint = showScrollWheelOverlayHint;
			this.apexPackOpen = apexPackOpen;
		}

		public Phase getPhase()
		{
			return phase;
		}

		public List<RevealCard> getCards()
		{
			return cards;
		}

		public long getPhaseElapsedMs()
		{
			return phaseElapsedMs;
		}

		public double getPackFadeProgress()
		{
			return packFadeProgress;
		}

		public String getBoosterPackId()
		{
			return boosterPackId;
		}

		/** Whether the first-pack scroll/zoom hint should be drawn this frame (10 seconds from reveal start). */
		public boolean isShowScrollWheelOverlayHint()
		{
			return showScrollWheelOverlayHint;
		}

		public boolean isApexPackOpen()
		{
			return apexPackOpen;
		}

		public boolean isCardRevealed(int index)
		{
			return index >= 0 && index < revealedByIndex.length && revealedByIndex[index];
		}

		/**
		 * True while any face-down card still qualifies for premium reveal audio (hum / reveal chime).
		 * @see PackRevealService#isPremiumRevealAudioPull(RevealCard)
		 */
		public boolean hasUnrevealedMythic()
		{
			for (int i = 0; i < cards.size(); i++)
			{
				boolean revealed = i < revealedByIndex.length && revealedByIndex[i];
				if (revealed)
				{
					continue;
				}
				RevealCard card = cards.get(i);
				if (isPremiumRevealAudioPull(card))
				{
					return true;
				}
			}
			return false;
		}
	}

	private static final long PACK_FADE_MS = 500L;
	private static final long SCROLL_WHEEL_HINT_DURATION_MS = 10_000L;

	/** Milliseconds between each card starting its flight from the pile. */
	public static final long PACK_DEAL_STAGGER_MS = 115L;
	/** Duration of each card's flight from pile to slot. */
	public static final long PACK_DEAL_FLIGHT_MS = 260L;

	private final CardDatabase cardDatabase;
	private final WikiImageCacheService imageCacheService;
	private final PackRevealSoundService packRevealSoundService;
	private final TcgPartyAnnouncer partyAnnouncer;

	private Phase phase = Phase.IDLE;
	private List<RevealCard> cards = List.of();
	private int revealedCount;
	private boolean[] revealedByIndex = new boolean[0];
	private long phaseStartedAt;
	private int cardPoolSize;
	private final Map<String, RarityMath.Tier> rarityTierByCardName = new HashMap<>();
	private final Map<RarityMath.Tier, Integer> tierPopulation = new EnumMap<>(RarityMath.Tier.class);
	private String boosterDisplayName = "";
	private String boosterPackId = "";
	/** When true, sealed-pack overlay uses apex hover sound and Godly-tier glow. */
	private boolean apexPackOpen;
	/** Wall-clock ms until which the first-pack scroll hint is shown; {@code 0} = off. */
	private long scrollWheelHintUntilMs;

	@Inject
	public PackRevealService(CardDatabase cardDatabase, WikiImageCacheService imageCacheService,
		PackRevealSoundService packRevealSoundService, TcgPartyAnnouncer partyAnnouncer)
	{
		this.cardDatabase = cardDatabase;
		this.imageCacheService = imageCacheService;
		this.packRevealSoundService = packRevealSoundService;
		this.partyAnnouncer = partyAnnouncer;
	}

	public synchronized void startReveal(List<PackCardResult> pulls)
	{
		startReveal(pulls, Set.of(), null, null, false, false);
	}

	public synchronized void startReveal(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards)
	{
		startReveal(pulls, preOwnedCards, null, null, false, false);
	}

	/**
	 * Rarity tiers match the collection album ({@link RarityMath#displayTierByCardName(List)} on the full loaded catalog).
	 * {@link RollPoolFilter} only affects the reported roll-pool size, not tier labels.
	 * Quest-only wiki items are omitted from {@code Card.json} at catalog build time.
	 */
	public synchronized void startReveal(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards, String boosterTitle)
	{
		startReveal(pulls, preOwnedCards, boosterTitle, null, false, false);
	}

	public synchronized void startReveal(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards,
		String boosterTitle, String boosterPackId)
	{
		startReveal(pulls, preOwnedCards, boosterTitle, boosterPackId, false, false);
	}

	public synchronized void startReveal(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards,
		String boosterTitle, String boosterPackId, boolean showScrollWheelOverlayHint)
	{
		startReveal(pulls, preOwnedCards, boosterTitle, boosterPackId, showScrollWheelOverlayHint, false);
	}

	public synchronized void startReveal(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards,
		String boosterTitle, String boosterPackId, boolean showScrollWheelOverlayHint, boolean apexPackOpen)
	{
		if (pulls == null || pulls.isEmpty())
		{
			reset();
			return;
		}

		packRevealSoundService.hardStop();

		cardDatabase.load();
		this.scrollWheelHintUntilMs = showScrollWheelOverlayHint
			? System.currentTimeMillis() + SCROLL_WHEEL_HINT_DURATION_MS
			: 0L;
		this.boosterDisplayName = boosterTitle == null ? "" : boosterTitle.trim();
		this.boosterPackId = boosterPackId == null ? "" : boosterPackId.trim();
		this.apexPackOpen = apexPackOpen;
		rebuildRarityTierIndex();

		List<RevealCard> resolved = new ArrayList<>();
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

			CardDefinition definition = findCard(pull.getCardName()).orElseGet(() ->
			{
				CardDefinition fallback = new CardDefinition();
				fallback.setName(pull.getCardName());
				fallback.setCategory(new ArrayList<>());
				fallback.setExamine("No examine text.");
				return fallback;
			});
			RarityMath.Tier tier = tierForCard(definition == null ? null : definition.getName());
			Color rarityColor = tier.getColor();
			long denominator = RarityMath.denominatorForTierCard(tier, tierPopulation.getOrDefault(tier, 1));
			boolean isNew = !preOwnedKeys.contains(normalizeKey(pull.getCardName(), pull.isFoil()));
			resolved.add(new RevealCard(pull, definition, tier, rarityColor, denominator, isNew));
		}

		Collections.shuffle(resolved, ThreadLocalRandom.current());
		this.cards = List.copyOf(resolved);
		imageCacheService.preload(this.cards.stream()
			.map(c -> c.getDefinition() == null ? null : c.getDefinition().getImageUrl())
			.collect(Collectors.toList()));
		this.revealedCount = 0;
		this.revealedByIndex = new boolean[this.cards.size()];
		this.phaseStartedAt = 0L;
		this.phase = cards.isEmpty() ? Phase.IDLE : Phase.PACK_READY;
	}

	public synchronized void handleClick(Point click, Rectangle packBounds, List<Rectangle> cardBounds)
	{
		if (phase == Phase.IDLE)
		{
			return;
		}

		if (phase == Phase.PACK_READY)
		{
			if (packBounds != null && click != null && packBounds.contains(click))
			{
				phase = Phase.PACK_FADING;
				phaseStartedAt = System.currentTimeMillis();
			}
			return;
		}

		if (phase == Phase.CARD_DEAL)
		{
			return;
		}

		// Fully revealed grid (or count says we're done): any click closes (WAIT_CLOSE, desync, stuck CARD_REVEAL).
		if (click != null && !cards.isEmpty() && (allRevealSlotsFaceUp() || revealedCount >= cards.size()))
		{
			reset();
			return;
		}

		if (phase == Phase.CARD_REVEAL && revealedCount < cards.size())
		{
			int clickedIndex = clickedCardIndex(cardBounds, click);
			if (clickedIndex >= 0 && clickedIndex < revealedByIndex.length && !revealedByIndex[clickedIndex])
			{
				RevealCard clicked = cards.get(clickedIndex);
				boolean highlightPull = isHighlightPull(clicked);
				revealedByIndex[clickedIndex] = true;
				revealedCount++;
				packRevealSoundService.playCardFlip();
				if (isPremiumRevealAudioPull(clicked))
				{
					packRevealSoundService.playMythicReveal();
				}
				if (highlightPull)
				{
					partyAnnouncer.announceMythicPull(cardNameForParty(clicked), clicked.isNew(), isFoilPull(clicked));
				}
				if (revealedCount >= cards.size())
				{
					phase = Phase.WAIT_CLOSE;
					phaseStartedAt = System.currentTimeMillis();
				}
			}
		}
	}

	/**
	 * Right-click shortcut: skip to all cards face-up and wait-for-close. Only runs after the deal animation has
	 * finished ({@link Phase#CARD_REVEAL} or later). Does nothing during sealed pack, fade, or while cards are still
	 * flying into the grid ({@link Phase#PACK_READY}, {@link Phase#PACK_FADING}, {@link Phase#CARD_DEAL}), or when
	 * already waiting to close ({@link Phase#WAIT_CLOSE}).
	 *
	 * @return {@code true} if reveal-all was applied (caller may refresh UI / consume the click)
	 */
	public synchronized boolean revealAllCards()
	{
		if (phase == Phase.IDLE || cards.isEmpty())
		{
			return false;
		}
		if (phase == Phase.PACK_READY || phase == Phase.PACK_FADING || phase == Phase.CARD_DEAL || phase == Phase.WAIT_CLOSE)
		{
			return false;
		}
		forceRevealAllAndWaitClose();
		return true;
	}

	/**
	 * Escape / Space progression: open sealed pack, reveal all cards, then close.
	 *
	 * @return {@code true} if the reveal session ended ({@link #reset()})
	 */
	public synchronized boolean advanceFromKeyboard()
	{
		if (phase == Phase.IDLE || cards.isEmpty())
		{
			return false;
		}

		if (phase == Phase.PACK_READY)
		{
			phase = Phase.PACK_FADING;
			phaseStartedAt = System.currentTimeMillis();
			return false;
		}

		if (phase == Phase.PACK_FADING || phase == Phase.CARD_DEAL
			|| (phase == Phase.CARD_REVEAL && revealedCount < cards.size()))
		{
			forceRevealAllAndWaitClose();
			return false;
		}

		reset();
		return true;
	}

	public synchronized void skip()
	{
		advanceFromKeyboard();
	}

	private void forceRevealAllAndWaitClose()
	{
		if (phase == Phase.CARD_REVEAL && revealedCount < cards.size())
		{
			packRevealSoundService.playCardFlip();
		}
		announcePartyMythicPullsForPreviouslyUnrevealedSlots();
		playMythicRevealIfAnyUnrevealedMythic();
		revealedCount = cards.size();
		for (int i = 0; i < revealedByIndex.length; i++)
		{
			revealedByIndex[i] = true;
		}
		phase = Phase.WAIT_CLOSE;
		phaseStartedAt = System.currentTimeMillis();
	}

	public synchronized void tick()
	{
		if (phase == Phase.PACK_FADING && phaseStartedAt > 0L && (System.currentTimeMillis() - phaseStartedAt) >= PACK_FADE_MS)
		{
			phase = Phase.CARD_DEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		else if (phase == Phase.CARD_DEAL && phaseStartedAt > 0L
			&& (System.currentTimeMillis() - phaseStartedAt) >= packDealPhaseTotalMs(cards.size()))
		{
			phase = Phase.CARD_REVEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		else if (phase == Phase.CARD_REVEAL && allRevealSlotsFaceUp())
		{
			phase = Phase.WAIT_CLOSE;
			phaseStartedAt = System.currentTimeMillis();
		}
	}

	/** Total time the overlay stays in {@link Phase#CARD_DEAL} before click-to-reveal begins. */
	public static long packDealPhaseTotalMs(int cardCount)
	{
		if (cardCount <= 0)
		{
			return 0L;
		}
		return (long) (cardCount - 1) * PACK_DEAL_STAGGER_MS + PACK_DEAL_FLIGHT_MS;
	}

	public synchronized boolean isActive()
	{
		return phase != Phase.IDLE && !cards.isEmpty();
	}

	/**
	 * Advances time-based transitions ({@link #tick()}), then returns immutable state for this paint frame.
	 */
	public synchronized Optional<RevealPaintSnapshot> capturePaintFrame()
	{
		tick();
		if (phase == Phase.IDLE || cards.isEmpty())
		{
			return Optional.empty();
		}
		long phaseElapsedMs = computePhaseElapsedMsLocked();
		double packFadeProgress = computePackFadeProgressLocked();
		boolean[] revCopy = Arrays.copyOf(revealedByIndex, revealedByIndex.length);
		boolean scrollHintVisible = System.currentTimeMillis() < scrollWheelHintUntilMs;
		return Optional.of(new RevealPaintSnapshot(
			phase,
			List.copyOf(cards),
			revCopy,
			phaseElapsedMs,
			packFadeProgress,
			boosterPackId,
			scrollHintVisible,
			apexPackOpen));
	}

	private long computePhaseElapsedMsLocked()
	{
		if (phaseStartedAt <= 0L)
		{
			return 0L;
		}
		return Math.max(0L, System.currentTimeMillis() - phaseStartedAt);
	}

	private double computePackFadeProgressLocked()
	{
		if (phase != Phase.PACK_FADING || phaseStartedAt <= 0L)
		{
			return phase == Phase.CARD_DEAL || phase == Phase.CARD_REVEAL || phase == Phase.WAIT_CLOSE ? 1.0d : 0.0d;
		}
		double elapsed = (double) (System.currentTimeMillis() - phaseStartedAt);
		return clamp01(elapsed / (double) PACK_FADE_MS);
	}

	public synchronized Phase getPhase()
	{
		return phase;
	}

	public synchronized List<RevealCard> getCards()
	{
		return cards;
	}

	public synchronized int getRevealedCount()
	{
		return revealedCount;
	}

	public synchronized boolean isCardRevealed(int index)
	{
		return index >= 0 && index < revealedByIndex.length && revealedByIndex[index];
	}

	/**
	 * True while any card that qualifies for premium reveal audio (hum / reveal chime) is still face-down
	 * (deal, click-to-reveal, or wait-to-close).
	 * @see #isPremiumRevealAudioPull(RevealCard)
	 */
	public synchronized boolean hasUnrevealedMythic()
	{
		for (int i = 0; i < cards.size(); i++)
		{
			boolean revealed = i < revealedByIndex.length && revealedByIndex[i];
			if (revealed)
			{
				continue;
			}
			RevealCard card = cards.get(i);
			if (isPremiumRevealAudioPull(card))
			{
				return true;
			}
		}
		return false;
	}

	public synchronized double getPackFadeProgress()
	{
		return computePackFadeProgressLocked();
	}

	public synchronized long getPhaseElapsedMs()
	{
		return computePhaseElapsedMsLocked();
	}

	public synchronized int getCardPoolSize()
	{
		return cardPoolSize;
	}

	public synchronized String getBoosterDisplayName()
	{
		return boosterDisplayName;
	}

	public synchronized String getBoosterPackId()
	{
		return boosterPackId;
	}

	public synchronized void reset()
	{
		phase = Phase.IDLE;
		cards = List.of();
		revealedCount = 0;
		revealedByIndex = new boolean[0];
		phaseStartedAt = 0L;
		cardPoolSize = 0;
		boosterDisplayName = "";
		boosterPackId = "";
		apexPackOpen = false;
		scrollWheelHintUntilMs = 0L;
	}

	/**
	 * Stops an active reveal (e.g. Safe-mode combat interrupt). Announces party highlights for unrevealed slots,
	 * then returns the pulled cards for chat. Cards are already in the collection from pack open.
	 */
	public synchronized List<RevealCard> abortActiveReveal()
	{
		if (!isActive())
		{
			return List.of();
		}
		announcePartyMythicPullsForPreviouslyUnrevealedSlots();
		List<RevealCard> snapshot = List.copyOf(cards);
		packRevealSoundService.hardStop();
		reset();
		return snapshot;
	}

	private double clamp01(double value)
	{
		if (value < 0.0d)
		{
			return 0.0d;
		}
		if (value > 1.0d)
		{
			return 1.0d;
		}
		return value;
	}

	private Optional<CardDefinition> findCard(String name)
	{
		return cardDatabase.getCards().stream()
			.filter(Objects::nonNull)
			.filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(name))
			.findFirst();
	}

	private int clickedCardIndex(List<Rectangle> bounds, Point click)
	{
		if (bounds == null || click == null)
		{
			return -1;
		}
		for (int i = 0; i < bounds.size(); i++)
		{
			Rectangle boundsAtIndex = bounds.get(i);
			if (boundsAtIndex != null && boundsAtIndex.contains(click))
			{
				return i;
			}
		}
		return -1;
	}

	private void rebuildRarityTierIndex()
	{
		rarityTierByCardName.clear();
		tierPopulation.clear();
		for (RarityMath.Tier tier : RarityMath.Tier.values())
		{
			tierPopulation.put(tier, 0);
		}

		List<CardDefinition> universe = cardDatabase.getCards();
		Map<String, RarityMath.Tier> tierByName = RarityMath.displayTierByCardName(universe);
		List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(universe);
		cardPoolSize = (int) rollPool.stream()
			.filter(card -> card != null && card.getName() != null && !card.getName().trim().isEmpty())
			.count();

		for (Map.Entry<String, RarityMath.Tier> entry : tierByName.entrySet())
		{
			String name = entry.getKey();
			if (name != null)
			{
				rarityTierByCardName.put(name.toLowerCase(), entry.getValue());
			}
		}

		for (CardDefinition card : universe)
		{
			if (card == null || card.getName() == null || card.getName().trim().isEmpty())
			{
				continue;
			}
			RarityMath.Tier tier = tierByName.getOrDefault(card.getName(), RarityMath.Tier.COMMON);
			tierPopulation.put(tier, tierPopulation.get(tier) + 1);
		}
	}

	private RarityMath.Tier tierForCard(String cardName)
	{
		if (cardName == null)
		{
			return RarityMath.Tier.COMMON;
		}
		return rarityTierByCardName.getOrDefault(cardName.toLowerCase(), RarityMath.Tier.COMMON);
	}

	private String normalizeKey(String cardName, boolean foil)
	{
		return (cardName == null ? "" : cardName.trim().toLowerCase()) + "|" + (foil ? "1" : "0");
	}

	private void playMythicRevealIfAnyUnrevealedMythic()
	{
		if (hasUnrevealedMythic())
		{
			packRevealSoundService.playMythicReveal();
		}
	}

	private void announcePartyMythicPullsForPreviouslyUnrevealedSlots()
	{
		for (int i = 0; i < cards.size(); i++)
		{
			if (i >= revealedByIndex.length || revealedByIndex[i])
			{
				continue;
			}
			RevealCard card = cards.get(i);
			if (isHighlightPull(card))
			{
				partyAnnouncer.announceMythicPull(cardNameForParty(card), card.isNew(), isFoilPull(card));
			}
		}
	}

	/**
	 * Party / emphasis: any Godly-tier pull or any foil (matches album “special” treatment elsewhere).
	 */
	private static boolean isHighlightPull(RevealCard card)
	{
		if (card == null)
		{
			return false;
		}
		if (card.getTier() == RarityMath.Tier.GODLY)
		{
			return true;
		}
		return card.getPull() != null && card.getPull().isFoil();
	}

	/**
	 * Hum loop + {@code reveal.wav}: any Godly-tier card, or a foil whose display tier is one of the three highest
	 * ({@link RarityMath.Tier#LEGENDARY}, {@link RarityMath.Tier#MYTHIC}, {@link RarityMath.Tier#GODLY}).
	 */
	private static boolean isPremiumRevealAudioPull(RevealCard card)
	{
		if (card == null)
		{
			return false;
		}
		if (card.getTier() == RarityMath.Tier.GODLY)
		{
			return true;
		}
		if (!isFoilPull(card))
		{
			return false;
		}
		return card.getTier().ordinal() >= RarityMath.Tier.LEGENDARY.ordinal();
	}

	private static boolean isFoilPull(RevealCard card)
	{
		return card != null && card.getPull() != null && card.getPull().isFoil();
	}

	private static String cardNameForParty(RevealCard card)
	{
		if (card.getDefinition() != null && card.getDefinition().getName() != null
			&& !card.getDefinition().getName().trim().isEmpty())
		{
			return card.getDefinition().getName().trim();
		}
		if (card.getPull() != null && card.getPull().getCardName() != null)
		{
			return card.getPull().getCardName().trim();
		}
		return "";
	}

	private boolean allRevealSlotsFaceUp()
	{
		if (cards.isEmpty() || revealedByIndex.length != cards.size())
		{
			return false;
		}
		for (int i = 0; i < revealedByIndex.length; i++)
		{
			if (!revealedByIndex[i])
			{
				return false;
			}
		}
		return true;
	}
}

package com.osrstcg.pack;

import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.catalog.PackImageUrls;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.util.CardDisplayNames;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.notify.PullNotificationService;

@Singleton
public class PackRevealService
{
	public enum Phase
	{
		IDLE,
		PACK_READY,
		PACK_FADING,
		/**
		 * Deal finished while server pulls are still outstanding - legacy hold used only when
		 * no pack-size placeholders were seeded (empty card list after fade). Prefer holding in
		 * {@link #CARD_DEAL} past the deal end so landed backs stay visible.
		 */
		AWAITING_PULLS,
		/** Cards fly from a central pile into the 2+3 grid (current batch of up to
		 * {@link #MAX_VISIBLE_REVEAL_CARDS}; may use placeholders until pulls land). */
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
		/** Eased flip progress 0..1 per slot (1 = face-up settled). */
		private final float[] flipProgressByIndex;
		private final long phaseElapsedMs;
		private final double packFadeProgress;
		private final String boosterPackId;
		private final boolean showScrollWheelOverlayHint;
		private final boolean apexPackOpen;

		private RevealPaintSnapshot(Phase phase, List<RevealCard> cards, boolean[] revealedByIndex,
			float[] flipProgressByIndex, long phaseElapsedMs,
			double packFadeProgress, String boosterPackId, boolean showScrollWheelOverlayHint, boolean apexPackOpen)
		{
			this.phase = phase;
			this.cards = cards;
			this.revealedByIndex = revealedByIndex;
			this.flipProgressByIndex = flipProgressByIndex;
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
		 * Eased 0..1 Y-flip progress for this slot. {@code 0} = face-down resting, {@code 1} = face-up settled.
		 * Mid-flip ({@code > 0} and {@code < 1}) should squash on X and swap texture after 90°.
		 */
		public float getFlipProgress(int index)
		{
			if (index < 0 || flipProgressByIndex == null || index >= flipProgressByIndex.length)
			{
				return isCardRevealed(index) ? 1f : 0f;
			}
			return flipProgressByIndex[index];
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
	/** Max cards dealt / shown at once during pack reveal (classic 2+3 grid). */
	public static final int MAX_VISIBLE_REVEAL_CARDS = 5;
	/** Max wait for cloud pack-open after {@link #beginPendingReveal} before aborting the overlay. */
	public static final long PENDING_PULLS_TIMEOUT_MS = 5_000L;
	/** Game-chat body (no {@code [OSRS TCG]} prefix) when {@link #PENDING_PULLS_TIMEOUT_MS} elapses. */
	public static final String PENDING_PULLS_TIMEOUT_MESSAGE =
		"There was a problem opening the pack at this time. Try again later.";

	private final CardDatabase cardDatabase;
	private final CardImageCacheService imageCacheService;
	private final PackCatalogService packCatalogService;
	private final PackRevealSoundService packRevealSoundService;
	private final PullNotificationService pullNotificationService;
	private final RevealCardResolver revealCardResolver;

	private Phase phase = Phase.IDLE;
	/** Full pack pulls (all batches). Overlay paint uses {@link #visibleCards()}. */
	private List<RevealCard> cards = List.of();
	/** Index into {@link #cards} for the first card of the current deal/reveal batch. */
	private int batchOffset;
	private int revealedCount;
	/** Face-up flags for the current batch only (length {@link #visibleCount()}). */
	private boolean[] revealedByIndex = new boolean[0];
	/**
	 * True when a collection-add chat line was already queued for that absolute pack slot
	 * (length {@code cards.size()}, spans all batches for Esc abort).
	 */
	private boolean[] collectionChatPostedByIndex = new boolean[0];
	/** Epoch ms when a Y-flip started for each current-batch slot; 0 = not flipping. */
	private long[] flipStartedAtMs = new long[0];
	/** Click-to-flip duration matching website {@code .card-inspect__flipper} (550ms). */
	public static final int CARD_FLIP_MS = 550;
	private boolean dinkEndNotificationsSent;
	private long phaseStartedAt;
	private String boosterPackId = "";
	/** When true, sealed-pack overlay uses apex hover sound and Godly-tier glow. */
	private boolean apexPackOpen;
	/** Wall-clock ms until which the first-pack scroll hint is shown; {@code 0} = off. */
	private long scrollWheelHintUntilMs;
	/** True after {@link #beginPendingReveal} until {@link #supplyRevealPulls} or abort. */
	private boolean awaitingServerPulls;
	/** Wall-clock start of the current pending open; {@code 0} when not awaiting. */
	private long pendingRevealStartedAtMs;
	/**
	 * Set when {@link #PENDING_PULLS_TIMEOUT_MS} elapses with no pulls; consumed by the overlay/UI
	 * so chat + sidebar cleanup run once.
	 */
	private boolean pendingPullsTimedOut;

	@Inject
	public PackRevealService(CardDatabase cardDatabase, CardImageCacheService imageCacheService,
		PackCatalogService packCatalogService, PackRevealSoundService packRevealSoundService,
		PullNotificationService pullNotificationService, CloudApiClient cloudApiClient)
	{
		this.cardDatabase = cardDatabase;
		this.imageCacheService = imageCacheService;
		this.packCatalogService = packCatalogService;
		this.packRevealSoundService = packRevealSoundService;
		this.pullNotificationService = pullNotificationService;
		this.revealCardResolver = new RevealCardResolver(cardDatabase, cloudApiClient);
	}

	/**
	 * Show the sealed-pack overlay immediately while the cloud pack RPC runs in the background.
	 * Seeds face-down placeholders so pack fade + deal can play before the response arrives.
	 * Call {@link #supplyRevealPulls} when the response arrives, or {@link #abortPendingReveal} on failure.
	 * Cloud pack opens supply {@code tierLabel}/{@code score}/{@code imagePath} on each pull;
	 * those drive rarity chrome and score text. Legacy pulls without {@code tierLabel} fall
	 * back to the catalog card's precomputed {@code tierLabel}.
	 */
	public synchronized void beginPendingReveal(String boosterTitle, String boosterPackId,
		boolean showScrollWheelOverlayHint, boolean apexPackOpen)
	{
		beginPendingReveal(boosterTitle, boosterPackId, showScrollWheelOverlayHint, apexPackOpen, 0);
	}

	public synchronized void beginPendingReveal(String boosterTitle, String boosterPackId,
		boolean showScrollWheelOverlayHint, boolean apexPackOpen, int expectedCardCount)
	{
		packRevealSoundService.hardStop();
		cardDatabase.load();
		this.scrollWheelHintUntilMs = showScrollWheelOverlayHint
			? System.currentTimeMillis() + SCROLL_WHEEL_HINT_DURATION_MS
			: 0L;
		this.boosterPackId = boosterPackId == null ? "" : boosterPackId.trim();
		this.apexPackOpen = apexPackOpen;
		preloadRevealSleeve(this.boosterPackId);
		List<RevealCard> placeholders = revealCardResolver.createPlaceholderCards(expectedCardCount);
		this.cards = placeholders;
		this.batchOffset = 0;
		this.collectionChatPostedByIndex = new boolean[placeholders.size()];
		initCurrentBatchRevealFlags();
		this.dinkEndNotificationsSent = false;
		this.phaseStartedAt = 0L;
		this.awaitingServerPulls = true;
		this.pendingRevealStartedAtMs = System.currentTimeMillis();
		this.pendingPullsTimedOut = false;
		revealCardResolver.rebuildRarityTierIndex();
		this.phase = Phase.PACK_READY;
	}

	/** Kick off a background load of the pack catalog {@code image} (not shop {@code thumbnail}). */
	private void preloadRevealSleeve(String packId)
	{
		if (imageCacheService == null || packCatalogService == null || packId == null || packId.isBlank())
		{
			return;
		}
		BoosterPackDefinition pack = packCatalogService.getCache().get(packId).orElse(null);
		String sleeve = PackImageUrls.revealSleevePath(pack);
		if (sleeve != null)
		{
			imageCacheService.preload(List.of(sleeve));
		}
	}

	/**
	 * Bind server pulls into an in-progress pending reveal. Safe during
	 * {@link Phase#PACK_READY}, {@link Phase#PACK_FADING}, {@link Phase#CARD_DEAL},
	 * or {@link Phase#AWAITING_PULLS}.
	 *
	 * @return false if pulls were empty / invalid (caller should abort)
	 */
	public synchronized boolean supplyRevealPulls(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards)
	{
		return supplyRevealPulls(pulls, preOwnedCards, apexPackOpen);
	}

	public synchronized boolean supplyRevealPulls(List<PackCardResult> pulls, Set<CardCollectionKey> preOwnedCards,
		boolean apexPackOpen)
	{
		if (phase == Phase.IDLE)
		{
			return false;
		}
		List<RevealCard> resolved = revealCardResolver.resolveRevealCards(pulls, preOwnedCards);
		if (resolved.isEmpty())
		{
			return false;
		}

		Collections.shuffle(resolved, ThreadLocalRandom.current());
		this.cards = List.copyOf(resolved);
		this.batchOffset = 0;
		this.apexPackOpen = apexPackOpen;
		imageCacheService.preload(this.cards.stream()
			.flatMap(c ->
			{
				CardDefinition def = c.getDefinition();
				if (def == null)
				{
					return java.util.stream.Stream.empty();
				}
				boolean foil = c.getPull() != null && c.getPull().isFoil();
				String foilPath = def.getFoilImagePath();
				if (foil && foilPath != null && !foilPath.isBlank())
				{
					return java.util.stream.Stream.of(foilPath);
				}
				return java.util.stream.Stream.of(def.getImageUrl());
			})
			.collect(Collectors.toList()));
		this.collectionChatPostedByIndex = new boolean[this.cards.size()];
		initCurrentBatchRevealFlags();
		this.dinkEndNotificationsSent = false;
		this.awaitingServerPulls = false;
		this.pendingRevealStartedAtMs = 0L;

		if (phase == Phase.AWAITING_PULLS
			|| (phase == Phase.PACK_FADING && phaseStartedAt > 0L
				&& (System.currentTimeMillis() - phaseStartedAt) >= PACK_FADE_MS))
		{
			// Pre-deal wait (no placeholders) or fade already finished - start spreading.
			phase = Phase.CARD_DEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		else if (phase == Phase.CARD_DEAL && phaseStartedAt > 0L
			&& (System.currentTimeMillis() - phaseStartedAt) >= packDealPhaseTotalMs(visibleCount()))
		{
			// Deal animation already finished while waiting on the RPC - unlock reveal.
			phase = Phase.CARD_REVEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		// PACK_READY / PACK_FADING (in progress) / CARD_DEAL (in progress): keep phase under animation.
		return true;
	}

	/** Cancel a pending / in-progress reveal when the pack RPC fails. */
	public synchronized void abortPendingReveal()
	{
		packRevealSoundService.hardStop();
		reset();
	}

	/**
	 * Prefer server {@code tierLabel} for cloud pack pulls; otherwise local catalog display tier.
	 */
	public RarityMath.Tier tierForPackPull(PackCardResult pull, String catalogCardName)
	{
		return revealCardResolver.tierForPackPull(pull, catalogCardName);
	}

	private static String cardNameForParty(RevealCard card)
	{
		return CardDisplayNames.titleForDefinition(
			card == null ? null : card.getDefinition(),
			card == null ? null : card.getPull());
	}

	private static String instanceIdFor(RevealCard card)
	{
		if (card == null || card.getPull() == null)
		{
			return null;
		}
		String id = card.getPull().getInstanceId();
		return id == null || id.isBlank() ? null : id.trim();
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

		if (phase == Phase.CARD_DEAL || phase == Phase.AWAITING_PULLS)
		{
			return;
		}

		int batchSize = visibleCount();
		// Fully revealed batch: advance to next deal or close the session.
		if (click != null && batchSize > 0 && (allRevealSlotsFaceUp() || revealedCount >= batchSize))
		{
			advancePastWaitClose();
			return;
		}

		if (phase == Phase.CARD_REVEAL && revealedCount < batchSize)
		{
			int clickedIndex = clickedCardIndex(cardBounds, click);
			if (clickedIndex >= 0 && clickedIndex < revealedByIndex.length
				&& !revealedByIndex[clickedIndex]
				&& (clickedIndex >= flipStartedAtMs.length || flipStartedAtMs[clickedIndex] <= 0L))
			{
				int absIndex = batchOffset + clickedIndex;
				RevealCard clicked = cards.get(absIndex);
				if (clickedIndex < flipStartedAtMs.length)
				{
					flipStartedAtMs[clickedIndex] = System.currentTimeMillis();
				}
				packRevealSoundService.playCardFlip();
				if (isPremiumRevealAudioPull(clicked))
				{
					packRevealSoundService.playMythicReveal();
				}
				if (pullNotificationService.notifyPull(
					cardNameForParty(clicked),
					clicked.isNew(),
					isFoilPull(clicked),
					clicked.getTier(),
					instanceIdFor(clicked))
					&& absIndex < collectionChatPostedByIndex.length)
				{
					collectionChatPostedByIndex[absIndex] = true;
				}
			}
		}
	}

	/**
	 * Space progression: open sealed pack, reveal current batch, then next batch or close.
	 *
	 * @return {@code true} if the reveal session ended ({@link #reset()})
	 */
	public synchronized boolean advanceFromKeyboard()
	{
		if (phase == Phase.IDLE)
		{
			return false;
		}

		if (phase == Phase.PACK_READY)
		{
			phase = Phase.PACK_FADING;
			phaseStartedAt = System.currentTimeMillis();
			return false;
		}

		int batchSize = visibleCount();
		if (phase == Phase.PACK_FADING || phase == Phase.AWAITING_PULLS || phase == Phase.CARD_DEAL
			|| (phase == Phase.CARD_REVEAL && revealedCount < batchSize))
		{
			if (awaitingServerPulls || cards.isEmpty() || !hasResolvablePulls())
			{
				// Still waiting on the server - don't skip into placeholder / empty reveal.
				return false;
			}
			forceRevealCurrentBatchAndWaitClose();
			return false;
		}

		return advancePastWaitClose();
	}

	/**
	 * From {@link Phase#WAIT_CLOSE} (or equivalent “batch done”): deal the next batch or end the session.
	 *
	 * @return {@code true} if the reveal session ended
	 */
	private boolean advancePastWaitClose()
	{
		if (hasMoreBatches())
		{
			startNextBatch();
			return false;
		}
		reset();
		return true;
	}

	private void forceRevealCurrentBatchAndWaitClose()
	{
		int batchSize = visibleCount();
		if (phase == Phase.CARD_REVEAL && revealedCount < batchSize)
		{
			packRevealSoundService.playCardFlip();
		}
		announcePartyMythicPullsForCurrentBatchUnrevealed();
		playMythicRevealIfAnyUnrevealedMythic();
		revealedCount = batchSize;
		for (int i = 0; i < revealedByIndex.length; i++)
		{
			revealedByIndex[i] = true;
		}
		for (int i = 0; i < flipStartedAtMs.length; i++)
		{
			flipStartedAtMs[i] = 0L;
		}
		if (!hasMoreBatches())
		{
			notifyDinkAtEndOnce();
		}
		phase = Phase.WAIT_CLOSE;
		phaseStartedAt = System.currentTimeMillis();
	}

	public synchronized void tick()
	{
		if (awaitingServerPulls
			&& pendingRevealStartedAtMs > 0L
			&& (System.currentTimeMillis() - pendingRevealStartedAtMs) >= PENDING_PULLS_TIMEOUT_MS)
		{
			pendingPullsTimedOut = true;
			packRevealSoundService.hardStop();
			reset();
			return;
		}

		if (phase == Phase.PACK_FADING && phaseStartedAt > 0L && (System.currentTimeMillis() - phaseStartedAt) >= PACK_FADE_MS)
		{
			if (cards.isEmpty())
			{
				// No pack-size placeholders - hold until supplyRevealPulls seeds cards + starts deal.
				phase = Phase.AWAITING_PULLS;
				phaseStartedAt = System.currentTimeMillis();
			}
			else
			{
				// Deal with real pulls or placeholders while the open RPC finishes.
				phase = Phase.CARD_DEAL;
				phaseStartedAt = System.currentTimeMillis();
			}
		}
		else if (phase == Phase.CARD_DEAL && phaseStartedAt > 0L
			&& (System.currentTimeMillis() - phaseStartedAt) >= packDealPhaseTotalMs(visibleCount()))
		{
			if (awaitingServerPulls || !hasResolvablePulls())
			{
				// Hold landed face-down cards (elapsed past deal end) until pulls arrive.
				return;
			}
			phase = Phase.CARD_REVEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		else if (phase == Phase.CARD_REVEAL && allRevealSlotsFaceUp())
		{
			enterWaitCloseAfterBatchFullyRevealed();
		}
	}

	/** True when every slot has a non-empty card name (not a deal placeholder). */
	private boolean hasResolvablePulls()
	{
		if (cards.isEmpty())
		{
			return false;
		}
		for (RevealCard card : cards)
		{
			if (card == null || card.getPull() == null)
			{
				return false;
			}
			String name = card.getPull().getCardName();
			if (name == null || name.trim().isEmpty())
			{
				return false;
			}
		}
		return true;
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
		return phase != Phase.IDLE;
	}

	/**
	 * Advances time-based transitions ({@link #tick()}), then returns immutable state for this paint frame.
	 */
	public synchronized Optional<RevealPaintSnapshot> capturePaintFrame()
	{
		tick();
		completeFinishedFlipsLocked();
		if (phase == Phase.IDLE)
		{
			return Optional.empty();
		}
		// Pack chrome (sealed / fading) can paint before placeholders or server cards arrive.
		if (cards.isEmpty()
			&& phase != Phase.PACK_READY
			&& phase != Phase.PACK_FADING
			&& phase != Phase.AWAITING_PULLS)
		{
			return Optional.empty();
		}
		long phaseElapsedMs = computePhaseElapsedMsLocked();
		double packFadeProgress = computePackFadeProgressLocked();
		boolean[] revCopy = Arrays.copyOf(revealedByIndex, revealedByIndex.length);
		float[] flipCopy = buildFlipProgressLocked();
		boolean scrollHintVisible = System.currentTimeMillis() < scrollWheelHintUntilMs;
		return Optional.of(new RevealPaintSnapshot(
			phase,
			List.copyOf(visibleCards()),
			revCopy,
			flipCopy,
			phaseElapsedMs,
			packFadeProgress,
			boosterPackId,
			scrollHintVisible,
			apexPackOpen));
	}

	/** Settle any Y-flips whose 550ms animation has finished. */
	private void completeFinishedFlipsLocked()
	{
		if (flipStartedAtMs.length == 0)
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean anyCompleted = false;
		for (int i = 0; i < flipStartedAtMs.length; i++)
		{
			if (flipStartedAtMs[i] <= 0L)
			{
				continue;
			}
			if (i < revealedByIndex.length && revealedByIndex[i])
			{
				flipStartedAtMs[i] = 0L;
				continue;
			}
			if (now - flipStartedAtMs[i] < CARD_FLIP_MS)
			{
				continue;
			}
			if (i < revealedByIndex.length && !revealedByIndex[i])
			{
				revealedByIndex[i] = true;
				revealedCount++;
				anyCompleted = true;
			}
			flipStartedAtMs[i] = 0L;
		}
		if (anyCompleted && phase == Phase.CARD_REVEAL && revealedCount >= visibleCount() && visibleCount() > 0)
		{
			enterWaitCloseAfterBatchFullyRevealed();
		}
	}

	private float[] buildFlipProgressLocked()
	{
		float[] out = new float[Math.max(revealedByIndex.length, flipStartedAtMs.length)];
		long now = System.currentTimeMillis();
		for (int i = 0; i < out.length; i++)
		{
			if (i < revealedByIndex.length && revealedByIndex[i])
			{
				out[i] = 1f;
				continue;
			}
			if (i >= flipStartedAtMs.length || flipStartedAtMs[i] <= 0L)
			{
				out[i] = 0f;
				continue;
			}
			float linear = (float) ((now - flipStartedAtMs[i]) / (double) CARD_FLIP_MS);
			out[i] = CardFlipEasing.flipEase(Math.max(0f, Math.min(1f, linear)));
		}
		return out;
	}

	static float flipEase(float t)
	{
		return CardFlipEasing.flipEase(t);
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
		if (phase == Phase.AWAITING_PULLS
			|| phase == Phase.CARD_DEAL
			|| phase == Phase.CARD_REVEAL
			|| phase == Phase.WAIT_CLOSE)
		{
			return 1.0d;
		}
		if (phase != Phase.PACK_FADING || phaseStartedAt <= 0L)
		{
			return 0.0d;
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
		return List.copyOf(visibleCards());
	}

	public synchronized boolean isCardRevealed(int index)
	{
		return index >= 0 && index < revealedByIndex.length && revealedByIndex[index];
	}

	/**
	 * True while any card in the current batch that qualifies for premium reveal audio (hum / reveal chime)
	 * is still face-down (deal, click-to-reveal, or wait-to-close).
	 * @see #isPremiumRevealAudioPull(RevealCard)
	 */
	public synchronized boolean hasUnrevealedMythic()
	{
		List<RevealCard> visible = visibleCards();
		for (int i = 0; i < visible.size(); i++)
		{
			boolean revealed = i < revealedByIndex.length && revealedByIndex[i];
			if (revealed)
			{
				continue;
			}
			RevealCard card = visible.get(i);
			if (isPremiumRevealAudioPull(card))
			{
				return true;
			}
		}
		return false;
	}

	public synchronized boolean isAwaitingServerPulls()
	{
		return awaitingServerPulls;
	}

	/**
	 * True once after a pending open hits {@link #PENDING_PULLS_TIMEOUT_MS} with no server pulls.
	 * Clears the latch so chat/UI cleanup runs a single time.
	 */
	public synchronized boolean consumePendingPullsTimeout()
	{
		if (!pendingPullsTimedOut)
		{
			return false;
		}
		pendingPullsTimedOut = false;
		return true;
	}

	public synchronized void reset()
	{
		phase = Phase.IDLE;
		cards = List.of();
		batchOffset = 0;
		revealedCount = 0;
		revealedByIndex = new boolean[0];
		collectionChatPostedByIndex = new boolean[0];
		flipStartedAtMs = new long[0];
		dinkEndNotificationsSent = false;
		phaseStartedAt = 0L;
		boosterPackId = "";
		apexPackOpen = false;
		scrollWheelHintUntilMs = 0L;
		awaitingServerPulls = false;
		pendingRevealStartedAtMs = 0L;
	}

	/**
	 * Stops an active reveal (Esc / combat interrupt). Announces party highlights for every still-unrevealed
	 * slot across remaining batches, then ensures every resolved pull has a collection-add chat line.
	 * Cards are already in the collection from pack open. Skips all remaining deal screens.
	 */
	public synchronized List<RevealCard> abortActiveReveal()
	{
		if (!isActive())
		{
			return List.of();
		}
		announcePartyMythicPullsForAllStillUnrevealed();
		announceRemainingCollectionAddsToChat();
		List<RevealCard> snapshot = List.copyOf(cards);
		packRevealSoundService.hardStop();
		reset();
		return snapshot;
	}

	/** Posts collection-add chat for any resolved pull that has not already been announced. */
	private void announceRemainingCollectionAddsToChat()
	{
		for (int i = 0; i < cards.size(); i++)
		{
			if (i < collectionChatPostedByIndex.length && collectionChatPostedByIndex[i])
			{
				continue;
			}
			RevealCard card = cards.get(i);
			if (!hasRealPullIdentity(card))
			{
				continue;
			}
			pullNotificationService.announceCollectionAddAlways(card);
			if (i < collectionChatPostedByIndex.length)
			{
				collectionChatPostedByIndex[i] = true;
			}
		}
	}

	private static boolean hasRealPullIdentity(RevealCard card)
	{
		return card != null
			&& card.getPull() != null
			&& card.getPull().getCardName() != null
			&& !card.getPull().getCardName().isBlank();
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

	private void playMythicRevealIfAnyUnrevealedMythic()
	{
		if (hasUnrevealedMythic())
		{
			packRevealSoundService.playMythicReveal();
		}
	}

	/** Party notify for face-down slots in the current batch only (Space skip within a page). */
	private void announcePartyMythicPullsForCurrentBatchUnrevealed()
	{
		for (int i = 0; i < revealedByIndex.length; i++)
		{
			if (revealedByIndex[i])
			{
				continue;
			}
			int absIndex = batchOffset + i;
			if (absIndex < 0 || absIndex >= cards.size())
			{
				continue;
			}
			RevealCard card = cards.get(absIndex);
			if (pullNotificationService.notifyPull(
				cardNameForParty(card),
				card.isNew(),
				isFoilPull(card),
				card.getTier(),
				instanceIdFor(card))
				&& absIndex < collectionChatPostedByIndex.length)
			{
				collectionChatPostedByIndex[absIndex] = true;
			}
		}
	}

	/**
	 * Party notify for every absolute pack slot that has not been face-up yet (Esc / interrupt).
	 * Prior batches are treated as revealed; current batch uses {@link #revealedByIndex}; later batches are all pending.
	 */
	private void announcePartyMythicPullsForAllStillUnrevealed()
	{
		for (int absIndex = 0; absIndex < cards.size(); absIndex++)
		{
			if (isAbsolutelyRevealed(absIndex))
			{
				continue;
			}
			RevealCard card = cards.get(absIndex);
			if (pullNotificationService.notifyPull(
				cardNameForParty(card),
				card.isNew(),
				isFoilPull(card),
				card.getTier(),
				instanceIdFor(card))
				&& absIndex < collectionChatPostedByIndex.length)
			{
				collectionChatPostedByIndex[absIndex] = true;
			}
		}
	}

	private boolean isAbsolutelyRevealed(int absIndex)
	{
		if (absIndex < batchOffset)
		{
			return true;
		}
		int local = absIndex - batchOffset;
		if (local >= 0 && local < revealedByIndex.length)
		{
			return revealedByIndex[local];
		}
		return false;
	}

	private void notifyDinkAtEndOnce()
	{
		if (dinkEndNotificationsSent)
		{
			return;
		}
		dinkEndNotificationsSent = true;
		pullNotificationService.notifyDinkAtEnd(cards);
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

	private boolean allRevealSlotsFaceUp()
	{
		int batchSize = visibleCount();
		if (batchSize <= 0 || revealedByIndex.length != batchSize)
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

	private int visibleCount()
	{
		if (cards.isEmpty() || batchOffset >= cards.size())
		{
			return 0;
		}
		return Math.min(MAX_VISIBLE_REVEAL_CARDS, cards.size() - batchOffset);
	}

	private List<RevealCard> visibleCards()
	{
		int n = visibleCount();
		if (n <= 0)
		{
			return List.of();
		}
		return cards.subList(batchOffset, batchOffset + n);
	}

	private boolean hasMoreBatches()
	{
		return batchOffset + visibleCount() < cards.size();
	}

	private void initCurrentBatchRevealFlags()
	{
		int n = visibleCount();
		revealedCount = 0;
		revealedByIndex = new boolean[n];
		flipStartedAtMs = new long[n];
	}

	private void startNextBatch()
	{
		batchOffset += visibleCount();
		initCurrentBatchRevealFlags();
		packRevealSoundService.resetDealMotionSounds();
		phase = Phase.CARD_DEAL;
		phaseStartedAt = System.currentTimeMillis();
	}

	private void enterWaitCloseAfterBatchFullyRevealed()
	{
		if (!hasMoreBatches())
		{
			notifyDinkAtEndOnce();
		}
		phase = Phase.WAIT_CLOSE;
		phaseStartedAt = System.currentTimeMillis();
	}
}

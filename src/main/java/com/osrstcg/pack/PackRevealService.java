package com.osrstcg.pack;

import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.overlay.PackRevealDealLayout;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.util.CardDisplayNames;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.notify.PullNotificationService;
import com.osrstcg.ui.tip.CardInfoTipModel;
import com.osrstcg.ui.SharedCardRenderer;

/**
 * State machine driving the pack-open reveal overlay: pack-tap animation, waiting for the server's pulls,
 * dealing cards in batches, per-card flip reveals, and the wait-to-close pause. All public methods are
 * {@code synchronized} on this instance and are expected to be called from the client thread; {@link #tick()}
 * and {@link #capturePaintFrame()} are driven once per paint frame to advance time-based phases.
 */
@Singleton
public class PackRevealService
{
	/** Reveal overlay phases, advanced by {@link #tick()} (time-driven) or user input ({@link #handleClick}, {@link #advanceFromKeyboard}). */
	public enum Phase
	{
		/** No reveal in progress. */
		IDLE,
		/** Pack sleeve shown, waiting for the player to click/tap it. */
		PACK_READY,
		/** Pack sleeve fading out after being tapped. */
		PACK_FADING,
		/** Fade finished but the server hasn't supplied pulls yet ({@link #supplyRevealPulls} not yet called). */
		AWAITING_PULLS,
		/** Cards animating into their dealt positions. */
		CARD_DEAL,
		/** Cards dealt and awaiting per-card flip clicks/keyboard advance. */
		CARD_REVEAL,
		/** Current batch fully revealed; waiting for input to start the next batch or close the overlay. */
		WAIT_CLOSE
	}

	/** One resolved reveal card: the raw server pull, its display definition, rarity tier/color, and new-to-collection flag. */
	@Value
	public static class RevealCard
	{
		PackCardResult pull;
		CardDefinition definition;
		RarityMath.Tier tier;
		Color rarityColor;
		boolean isNew;
	}

	/** Immutable copy of the current reveal state for the overlay to paint one frame from, without holding the service lock. */
	@Getter
	public static final class RevealPaintSnapshot
	{
		private final Phase phase;
		private final List<RevealCard> cards;
		@Getter(AccessLevel.NONE)
		private final boolean[] revealedByIndex;
		@Getter(AccessLevel.NONE)
		private final float[] flipProgressByIndex;
		private final long phaseElapsedMs;
		private final double packFadeProgress;
		private final String boosterPackId;
		private final boolean apexPackOpen;

		/** Copies the given frame state; array/collection ownership is transferred from the caller. */
		private RevealPaintSnapshot(Phase phase, List<RevealCard> cards, boolean[] revealedByIndex,
			float[] flipProgressByIndex, long phaseElapsedMs,
			double packFadeProgress, String boosterPackId, boolean apexPackOpen)
		{
			this.phase = phase;
			this.cards = cards;
			this.revealedByIndex = revealedByIndex;
			this.flipProgressByIndex = flipProgressByIndex;
			this.phaseElapsedMs = phaseElapsedMs;
			this.packFadeProgress = packFadeProgress;
			this.boosterPackId = boosterPackId == null ? "" : boosterPackId;
			this.apexPackOpen = apexPackOpen;
		}

		/** Whether the visible card at {@code index} has completed its flip. */
		public boolean isCardRevealed(int index)
		{
			return index >= 0 && index < revealedByIndex.length && revealedByIndex[index];
		}

		/** Eased flip progress (0..1) for the visible card at {@code index}; 1 once revealed, 0 if out of range. */
		public float getFlipProgress(int index)
		{
			if (index < 0 || flipProgressByIndex == null || index >= flipProgressByIndex.length)
			{
				return isCardRevealed(index) ? 1f : 0f;
			}
			return flipProgressByIndex[index];
		}

		/** Whether any still-face-down card in this batch would play mythic/legendary-foil reveal audio when flipped. */
		public boolean hasUnrevealedMythic()
		{
			return hasUnrevealedPremiumAudio(cards, revealedByIndex);
		}
	}

	private static final long PACK_FADE_MS = 500L;

	public static final long PACK_DEAL_STAGGER_MS = 115L;
	public static final long PACK_DEAL_FLIGHT_MS = 260L;
	public static final int MAX_VISIBLE_REVEAL_CARDS = 5;
	public static final long PENDING_PULLS_TIMEOUT_MS = 5_000L;
	public static final String PENDING_PULLS_TIMEOUT_MESSAGE =
		"There was a problem opening the pack at this time. Try again later.";

	private final CardImageCacheService imageCacheService;
	private final PackCatalogService packCatalogService;
	private final PackRevealSoundService packRevealSoundService;
	private final PullNotificationService pullNotificationService;
	private final RevealCardResolver revealCardResolver;

	private Phase phase = Phase.IDLE;
	private List<RevealCard> cards = List.of();
	private int batchOffset;
	private int revealedCount;
	private boolean[] revealedByIndex = new boolean[0];
	private boolean[] collectionChatPosted = new boolean[0];
	private long[] flipStartedAtMs = new long[0];
	public static final int CARD_FLIP_MS = 550;
	private long phaseStartedAt;
	private String boosterPackId = "";
	private boolean apexPackOpen;
	private boolean awaitingServerPulls;
	private long pendingRevealStartedAtMs;
	private boolean pendingPullsTimedOut;
	private Set<String> preOwnedFoilNames = Set.of();

	@Inject
	public PackRevealService(CardDatabase cardDatabase, CardImageCacheService imageCacheService,
		PackCatalogService packCatalogService, PackRevealSoundService packRevealSoundService,
		PullNotificationService pullNotificationService)
	{
		this.imageCacheService = imageCacheService;
		this.packCatalogService = packCatalogService;
		this.packRevealSoundService = packRevealSoundService;
		this.pullNotificationService = pullNotificationService;
		this.revealCardResolver = new RevealCardResolver(cardDatabase);
	}

	/**
	 * Starts a reveal in {@link Phase#PACK_READY} with placeholder cards, before the server has responded
	 * with actual pulls. Call {@link #supplyRevealPulls} once the pulls arrive, or {@link #abortPendingReveal}
	 * if the buy call fails.
	 */
	public synchronized void beginPendingReveal(String boosterPackId,
		boolean apexPackOpen, int expectedCardCount)
	{
		packRevealSoundService.hardStop();
		this.boosterPackId = boosterPackId == null ? "" : boosterPackId.trim();
		this.apexPackOpen = apexPackOpen;
		preloadRevealSleeve(this.boosterPackId);
		List<RevealCard> placeholders = revealCardResolver.createPlaceholderCards(expectedCardCount);
		this.cards = placeholders;
		this.batchOffset = 0;
		this.collectionChatPosted = new boolean[placeholders.size()];
		initCurrentBatchRevealFlags();
		this.phaseStartedAt = 0L;
		this.awaitingServerPulls = true;
		this.pendingRevealStartedAtMs = System.currentTimeMillis();
		this.pendingPullsTimedOut = false;
		revealCardResolver.rebuildRarityTierIndex();
		this.phase = Phase.PACK_READY;
	}

	/** Kicks off async preloading of the card-back and (if resolvable) pack-specific reveal sleeve images. */
	private void preloadRevealSleeve(String packId)
	{
		ArrayList<String> urls = new ArrayList<>(2);
		urls.add(SharedCardRenderer.CARD_BACK_PATH);
		if (packId != null && !packId.isBlank())
		{
			BoosterPackDefinition pack = packCatalogService.getCache().get(packId).orElse(null);
			String sleeve = pack == null ? null : pack.revealSleevePath();
			if (sleeve != null)
			{
				urls.add(sleeve);
			}
		}
		imageCacheService.preloadAsync(urls);
	}

	/**
	 * Async continuation of {@link #beginPendingReveal}: resolves the server's pulls into shuffled
	 * {@link RevealCard}s, preloads their images, and advances the phase out of {@code AWAITING_PULLS}/
	 * pending {@code CARD_DEAL}/{@code CARD_REVEAL} if the corresponding wait has already elapsed.
	 * Returns {@code false} (leaving state untouched) if the reveal was aborted or the pulls resolved to nothing.
	 */
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

		preOwnedFoilNames = buildPreOwnedFoilNames(preOwnedCards);
		Collections.shuffle(resolved, ThreadLocalRandom.current());
		this.cards = List.copyOf(resolved);
		this.batchOffset = 0;
		this.apexPackOpen = apexPackOpen;
		imageCacheService.preloadAsync(this.cards.stream()
			.flatMap(c ->
			{
				CardDefinition def = c.getDefinition();
				if (def == null)
				{
					return Stream.empty();
				}
				boolean foil = c.getPull() != null && c.getPull().isFoil();
				String foilPath = def.getFoilImagePath();
				if (foil && foilPath != null && !foilPath.isBlank())
				{
					return Stream.of(foilPath);
				}
				return Stream.of(def.getImageUrl());
			})
			.collect(Collectors.toList()));
		this.collectionChatPosted = new boolean[this.cards.size()];
		initCurrentBatchRevealFlags();
		this.awaitingServerPulls = false;
		this.pendingRevealStartedAtMs = 0L;

		if (phase == Phase.AWAITING_PULLS
			|| (phase == Phase.PACK_FADING && phaseStartedAt > 0L
				&& (System.currentTimeMillis() - phaseStartedAt) >= PACK_FADE_MS))
		{
			phase = Phase.CARD_DEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		else if (phase == Phase.CARD_DEAL && phaseStartedAt > 0L
			&& (System.currentTimeMillis() - phaseStartedAt) >= packDealPhaseTotalMs(visibleCount()))
		{
			phase = Phase.CARD_REVEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		return true;
	}

	/** Cancels a reveal that never received server pulls (buy call failed) and returns to {@link Phase#IDLE}. */
	public synchronized void abortPendingReveal()
	{
		packRevealSoundService.hardStop();
		reset();
	}

	/** Display name used for the pull/party chat announcement for a revealed card. */
	private static String cardNameForParty(RevealCard card)
	{
		return CardDisplayNames.titleForDefinition(
			card == null ? null : card.getDefinition(),
			card == null ? null : card.getPull());
	}

	/**
	 * Handles a click/tap on the reveal overlay: taps the pack sleeve to start fading it, clicks past a
	 * fully-revealed batch to advance, or clicks an individual face-down card to start its flip (playing
	 * flip/mythic sound and posting the pull notification).
	 */
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
				notifyPullAndMarkPosted(clicked, absIndex);
			}
		}
	}

	/**
	 * Keyboard equivalent of tapping through the reveal: starts the pack fade, force-reveals the current
	 * batch (skipping the deal/flip animation) if pulls are resolvable, or advances past a finished batch.
	 * @return {@code true} if this call closed the reveal overlay entirely
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
				return false;
			}
			forceRevealBatchAndWaitClose();
			return false;
		}

		return advancePastWaitClose();
	}

	/** Starts the next card batch if any remain, otherwise closes the reveal. @return {@code true} if the reveal closed */
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

	/** Marks every card in the current batch revealed immediately, announces pulls, and enters {@link Phase#WAIT_CLOSE}. */
	private void forceRevealBatchAndWaitClose()
	{
		int batchSize = visibleCount();
		if (phase == Phase.CARD_REVEAL && revealedCount < batchSize)
		{
			packRevealSoundService.playCardFlip();
		}
		announcePartyUnrevealedPulls(true);
		if (hasUnrevealedPremiumAudio(visibleCards(), revealedByIndex))
		{
			packRevealSoundService.playMythicReveal();
		}
		revealedCount = batchSize;
		for (int i = 0; i < revealedByIndex.length; i++)
		{
			revealedByIndex[i] = true;
		}
		for (int i = 0; i < flipStartedAtMs.length; i++)
		{
			flipStartedAtMs[i] = 0L;
		}
		notifyPackAtBatchEnd();
		phase = Phase.WAIT_CLOSE;
		phaseStartedAt = System.currentTimeMillis();
	}

	/**
	 * Timer-driven: advances time-based phase transitions (pending-pulls timeout, pack fade to deal/await,
	 * deal to reveal, reveal to wait-close once fully face-up). Called once per paint frame via
	 * {@link #capturePaintFrame()}.
	 */
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
				phase = Phase.AWAITING_PULLS;
				phaseStartedAt = System.currentTimeMillis();
			}
			else
			{
				phase = Phase.CARD_DEAL;
				phaseStartedAt = System.currentTimeMillis();
			}
		}
		else if (phase == Phase.CARD_DEAL && phaseStartedAt > 0L
			&& (System.currentTimeMillis() - phaseStartedAt) >= packDealPhaseTotalMs(visibleCount()))
		{
			if (awaitingServerPulls || !hasResolvablePulls())
			{
				return;
			}
			phase = Phase.CARD_REVEAL;
			phaseStartedAt = System.currentTimeMillis();
		}
		else if (phase == Phase.CARD_REVEAL && allRevealSlotsFaceUp())
		{
			enterWaitCloseAfterBatch();
		}
	}

	/** Whether every card in the batch has a real (non-placeholder) pull identity, i.e. the server has responded. */
	private boolean hasResolvablePulls()
	{
		if (cards.isEmpty())
		{
			return false;
		}
		for (RevealCard card : cards)
		{
			if (!hasRealPullIdentity(card))
			{
				return false;
			}
		}
		return true;
	}

	/** Total duration of the deal animation for a batch of {@code cardCount} cards (stagger between cards plus one flight). */
	public static long packDealPhaseTotalMs(int cardCount)
	{
		if (cardCount <= 0)
		{
			return 0L;
		}
		return (long) (cardCount - 1) * PACK_DEAL_STAGGER_MS + PACK_DEAL_FLIGHT_MS;
	}

	/** Whether a reveal is in progress (any phase other than {@link Phase#IDLE}). */
	public synchronized boolean isActive()
	{
		return phase != Phase.IDLE;
	}

	/**
	 * Timer-driven: advances the state machine ({@link #tick()}, completing any finished card flips) and
	 * returns an immutable snapshot of the current frame for the overlay to paint, or empty if there's
	 * nothing to draw.
	 */
	public synchronized Optional<RevealPaintSnapshot> capturePaintFrame()
	{
		tick();
		completeFinishedFlipsLocked();
		if (phase == Phase.IDLE)
		{
			return Optional.empty();
		}
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
		return Optional.of(new RevealPaintSnapshot(
			phase,
			List.copyOf(visibleCards()),
			revCopy,
			flipCopy,
			phaseElapsedMs,
			packFadeProgress,
			boosterPackId,
			apexPackOpen));
	}

	/**
	 * Marks any in-progress card flip revealed once {@link #CARD_FLIP_MS} has elapsed since it started, and
	 * enters {@link Phase#WAIT_CLOSE} if that completes the batch.
	 */
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
			enterWaitCloseAfterBatch();
		}
	}

	/** Computes per-card eased flip progress (1 if revealed, 0 if not started, eased elapsed fraction otherwise). */
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

	/** Milliseconds elapsed since the current phase began, or 0 if the phase has no start time. */
	private long computePhaseElapsedMsLocked()
	{
		if (phaseStartedAt <= 0L)
		{
			return 0L;
		}
		return Math.max(0L, System.currentTimeMillis() - phaseStartedAt);
	}

	/** Pack-sleeve fade progress (0..1): 1 once past the fade, in-progress fraction during {@code PACK_FADING}, else 0. */
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
		return PackRevealDealLayout.clamp01(elapsed / (double) PACK_FADE_MS);
	}

	/** Current reveal phase. */
	public synchronized Phase getPhase()
	{
		return phase;
	}

	/** Cards in the currently visible batch (up to {@link #MAX_VISIBLE_REVEAL_CARDS}). */
	public synchronized List<RevealCard> getCards()
	{
		return List.copyOf(visibleCards());
	}

	/** Whether the visible card at {@code index} has completed its flip. */
	public synchronized boolean isCardRevealed(int index)
	{
		return index >= 0 && index < revealedByIndex.length && revealedByIndex[index];
	}

	/** Whether a reveal has started but the server's pulls haven't arrived yet. */
	public synchronized boolean isAwaitingServerPulls()
	{
		return awaitingServerPulls;
	}

	/** Consumes (clears) and returns whether the pending-pulls wait timed out since the last call. */
	public synchronized boolean consumePendingPullsTimeout()
	{
		if (!pendingPullsTimedOut)
		{
			return false;
		}
		pendingPullsTimedOut = false;
		return true;
	}

	/** Clears all reveal state and returns to {@link Phase#IDLE}. */
	public synchronized void reset()
	{
		phase = Phase.IDLE;
		cards = List.of();
		batchOffset = 0;
		revealedCount = 0;
		revealedByIndex = new boolean[0];
		collectionChatPosted = new boolean[0];
		flipStartedAtMs = new long[0];
		phaseStartedAt = 0L;
		boosterPackId = "";
		apexPackOpen = false;
		awaitingServerPulls = false;
		pendingRevealStartedAtMs = 0L;
		preOwnedFoilNames = Set.of();
	}

	/** Lower-cased names of cards the player already owned as foils before this reveal, for "already have" UI hints. */
	public synchronized Set<String> getPreOwnedFoilNames()
	{
		return Set.copyOf(preOwnedFoilNames);
	}

	/** Extracts lower-cased, normalized names of foil cards from the pre-reveal owned-cards set. */
	private static Set<String> buildPreOwnedFoilNames(Set<CardCollectionKey> preOwnedCards)
	{
		if (preOwnedCards == null || preOwnedCards.isEmpty())
		{
			return Set.of();
		}
		return preOwnedCards.stream()
			.filter(Objects::nonNull)
			.filter(CardCollectionKey::isFoil)
			.map(CardCollectionKey::getCardName)
			.filter(name -> name != null && !name.isBlank())
			.map(name -> name.trim().toLowerCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Force-closes an in-progress reveal (e.g. plugin shutdown or forced navigation away): announces any
	 * unrevealed pulls and pending collection chat, stops sounds, resets to idle, and returns the full
	 * card list that was in play.
	 */
	public synchronized List<RevealCard> abortActiveReveal()
	{
		if (!isActive())
		{
			return List.of();
		}
		announcePartyUnrevealedPulls(false);
		announceRemainingCollectionChat();
		List<RevealCard> snapshot = List.copyOf(cards);
		packRevealSoundService.hardStop();
		reset();
		return snapshot;
	}

	/** Posts the collection-add chat message for any real pull that hasn't had one posted yet. */
	private void announceRemainingCollectionChat()
	{
		for (int i = 0; i < cards.size(); i++)
		{
			if (i < collectionChatPosted.length && collectionChatPosted[i])
			{
				continue;
			}
			RevealCard card = cards.get(i);
			if (!hasRealPullIdentity(card))
			{
				continue;
			}
			pullNotificationService.postCollectionAddChat(card);
			if (i < collectionChatPosted.length)
			{
				collectionChatPosted[i] = true;
			}
		}
	}

	/** Whether {@code card} carries an actual server pull (as opposed to a placeholder awaiting server pulls). */
	private static boolean hasRealPullIdentity(RevealCard card)
	{
		return card != null
			&& card.getPull() != null
			&& card.getPull().getCardName() != null
			&& !card.getPull().getCardName().isBlank();
	}

	/** Posts the pull notification for {@code card} and marks its collection chat as posted if it was sent. */
	private void notifyPullAndMarkPosted(RevealCard card, int absIndex)
	{
		if (pullNotificationService.notifyPull(
			cardNameForParty(card),
			card.isNew(),
			isFoilPull(card),
			card.getTier(),
			CardInfoTipModel.instanceIdFor(card))
			&& absIndex < collectionChatPosted.length)
		{
			collectionChatPosted[absIndex] = true;
		}
	}

	/** Whether any not-yet-revealed card in {@code cards} would trigger premium (mythic/legendary-foil) reveal audio. */
	private static boolean hasUnrevealedPremiumAudio(List<RevealCard> cards, boolean[] revealedByIndex)
	{
		for (int i = 0; i < cards.size(); i++)
		{
			boolean revealed = i < revealedByIndex.length && revealedByIndex[i];
			if (revealed)
			{
				continue;
			}
			if (isPremiumRevealAudioPull(cards.get(i)))
			{
				return true;
			}
		}
		return false;
	}

	/** Index of the card bounds rectangle containing {@code click}, or -1 if none matched. */
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

	/**
	 * Posts pull notifications for cards not yet revealed. When {@code currentBatchOnly} is true, limits
	 * this to the visible batch (used when force-revealing it); otherwise covers every unrevealed card
	 * across the whole reveal (used when aborting).
	 */
	private void announcePartyUnrevealedPulls(boolean currentBatchOnly)
	{
		if (currentBatchOnly)
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
				notifyPullAndMarkPosted(cards.get(absIndex), absIndex);
			}
			return;
		}
		for (int absIndex = 0; absIndex < cards.size(); absIndex++)
		{
			if (isAbsolutelyRevealed(absIndex))
			{
				continue;
			}
			notifyPullAndMarkPosted(cards.get(absIndex), absIndex);
		}
	}

	/** Notifies party/chat of the full pack contents once every 5th card position is reached (i.e. pack fully revealed). */
	private void notifyPackAtBatchEnd()
	{
		if ((batchOffset + visibleCount()) % 5 == 0)
		{
			pullNotificationService.notifyPackAtEnd(List.copyOf(visibleCards()));
		}
	}

	/** Whether the card at absolute index {@code absIndex} (across all batches) has been revealed. */
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

	/** Whether {@code card} should play the premium reveal sting: any Godly pull, or a foil Legendary+ pull. */
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

	/** Whether {@code card}'s underlying pull is a foil. */
	private static boolean isFoilPull(RevealCard card)
	{
		return card != null && card.getPull() != null && card.getPull().isFoil();
	}

	/** Whether every slot in the current batch is revealed (and the batch is fully sized/populated). */
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

	/** Number of cards visible in the current batch, capped at {@link #MAX_VISIBLE_REVEAL_CARDS}. */
	private int visibleCount()
	{
		if (cards.isEmpty() || batchOffset >= cards.size())
		{
			return 0;
		}
		return Math.min(MAX_VISIBLE_REVEAL_CARDS, cards.size() - batchOffset);
	}

	/** The sublist of {@link #cards} making up the current batch. */
	private List<RevealCard> visibleCards()
	{
		int n = visibleCount();
		if (n <= 0)
		{
			return List.of();
		}
		return cards.subList(batchOffset, batchOffset + n);
	}

	/** Whether cards remain beyond the current batch. */
	private boolean hasMoreBatches()
	{
		return batchOffset + visibleCount() < cards.size();
	}

	/** (Re)allocates the per-slot revealed/flip-start arrays for the current batch and resets the revealed count. */
	private void initCurrentBatchRevealFlags()
	{
		int n = visibleCount();
		revealedCount = 0;
		revealedByIndex = new boolean[n];
		flipStartedAtMs = new long[n];
	}

	/** Advances the batch offset past the current batch and begins dealing the next one. */
	private void startNextBatch()
	{
		batchOffset += visibleCount();
		initCurrentBatchRevealFlags();
		packRevealSoundService.resetDealMotionSounds();
		phase = Phase.CARD_DEAL;
		phaseStartedAt = System.currentTimeMillis();
	}

	/** Notifies the full pack contents if this batch completes it, then enters {@link Phase#WAIT_CLOSE}. */
	private void enterWaitCloseAfterBatch()
	{
		notifyPackAtBatchEnd();
		phase = Phase.WAIT_CLOSE;
		phaseStartedAt = System.currentTimeMillis();
	}
}

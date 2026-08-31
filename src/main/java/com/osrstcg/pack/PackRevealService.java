package com.osrstcg.pack;

import com.osrstcg.cloud.api.CloudApiClient;
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

@Singleton
public class PackRevealService
{
	public enum Phase
	{
		IDLE,
		PACK_READY,
		PACK_FADING,
		AWAITING_PULLS,
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

		public boolean isCardRevealed(int index)
		{
			return index >= 0 && index < revealedByIndex.length && revealedByIndex[index];
		}

		public float getFlipProgress(int index)
		{
			if (index < 0 || flipProgressByIndex == null || index >= flipProgressByIndex.length)
			{
				return isCardRevealed(index) ? 1f : 0f;
			}
			return flipProgressByIndex[index];
		}

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
	private boolean[] collectionChatPostedByIndex = new boolean[0];
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
		PullNotificationService pullNotificationService, CloudApiClient cloudApiClient)
	{
		this.imageCacheService = imageCacheService;
		this.packCatalogService = packCatalogService;
		this.packRevealSoundService = packRevealSoundService;
		this.pullNotificationService = pullNotificationService;
		this.revealCardResolver = new RevealCardResolver(cardDatabase, cloudApiClient);
	}

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
		this.collectionChatPostedByIndex = new boolean[placeholders.size()];
		initCurrentBatchRevealFlags();
		this.phaseStartedAt = 0L;
		this.awaitingServerPulls = true;
		this.pendingRevealStartedAtMs = System.currentTimeMillis();
		this.pendingPullsTimedOut = false;
		revealCardResolver.rebuildRarityTierIndex();
		this.phase = Phase.PACK_READY;
	}

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
		imageCacheService.preload(urls);
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

		preOwnedFoilNames = buildPreOwnedFoilNames(preOwnedCards);
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
		this.collectionChatPostedByIndex = new boolean[this.cards.size()];
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

	public synchronized void abortPendingReveal()
	{
		packRevealSoundService.hardStop();
		reset();
	}

	private static String cardNameForParty(RevealCard card)
	{
		return CardDisplayNames.titleForDefinition(
			card == null ? null : card.getDefinition(),
			card == null ? null : card.getPull());
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
			forceRevealCurrentBatchAndWaitClose();
			return false;
		}

		return advancePastWaitClose();
	}

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
			enterWaitCloseAfterBatchFullyRevealed();
		}
	}

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
		return PackRevealDealLayout.clamp01(elapsed / (double) PACK_FADE_MS);
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

	public synchronized boolean isAwaitingServerPulls()
	{
		return awaitingServerPulls;
	}

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
		phaseStartedAt = 0L;
		boosterPackId = "";
		apexPackOpen = false;
		awaitingServerPulls = false;
		pendingRevealStartedAtMs = 0L;
		preOwnedFoilNames = Set.of();
	}

	public synchronized Set<String> getPreOwnedFoilNames()
	{
		return Set.copyOf(preOwnedFoilNames);
	}

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

	private void notifyPullAndMarkPosted(RevealCard card, int absIndex)
	{
		if (pullNotificationService.notifyPull(
			cardNameForParty(card),
			card.isNew(),
			isFoilPull(card),
			card.getTier(),
			CardInfoTipModel.instanceIdFor(card))
			&& absIndex < collectionChatPostedByIndex.length)
		{
			collectionChatPostedByIndex[absIndex] = true;
		}
	}

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
			notifyPullAndMarkPosted(cards.get(absIndex), absIndex);
		}
	}

	private void announcePartyMythicPullsForAllStillUnrevealed()
	{
		for (int absIndex = 0; absIndex < cards.size(); absIndex++)
		{
			if (isAbsolutelyRevealed(absIndex))
			{
				continue;
			}
			notifyPullAndMarkPosted(cards.get(absIndex), absIndex);
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
		phase = Phase.WAIT_CLOSE;
		phaseStartedAt = System.currentTimeMillis();
	}
}

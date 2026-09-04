package com.osrstcg.overlay;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.pack.PackRevealSoundService;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.ui.SharedCardRenderer;
import com.osrstcg.ui.card.CardColorMath;
import com.osrstcg.ui.card.CardFaceDrawRequest;
import com.osrstcg.ui.card.FoilFx;
import com.osrstcg.ui.card.WearFx;
import com.osrstcg.ui.tip.CardInfoTipModel;
import com.osrstcg.ui.tip.CardInfoTipPainter;
import com.osrstcg.util.OsrsWiki;
import com.osrstcg.util.PackRevealZoomUtil;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.LinkBrowser;
/**
 * Always-on-top {@link Overlay} that renders the full pack-opening animation: sealed pack, deal-out,
 * flip-reveal, hover/zoom effects, and the card info tooltip/close button chrome. Registered with the
 * RuneLite {@code OverlayManager} for the lifetime of the plugin; {@link #render(Graphics2D)} is a
 * no-op (and clears animation state) whenever {@link PackRevealService} has no active reveal to paint.
 * {@link #render(Graphics2D)} and its private helpers run on the client's rendering thread. A few
 * fields ({@link #sessionPackZoomMultiplier}, the hover-listener fields) are written from the input
 * listener thread and read during render, so they are {@code volatile} or otherwise designed to
 * tolerate that cross-thread access; everything else is render-thread-only.
 */
@Singleton
public class PackRevealOverlay extends Overlay
{
/** Scale multiplier applied to a face-up card on full hover lift. */
	private static final double HOVER_CARD_SCALE = 1.072d;
/** Scale multiplier applied to the sealed pack image on full hover lift. */
	private static final double PACK_IMAGE_HOVER_MAX_SCALE = 1.085d;
/** Per-reference-frame hover lift lerp factor; scaled to actual frame time by {@link #advanceHoverLerpFactor()}. */
	private static final double HOVER_LERP = 0.22d;
/** Frame rate the {@link #HOVER_LERP} constant was tuned against. */
	private static final double HOVER_LERP_REFERENCE_HZ = 60.0d;
/** Upper bound on the delta-time used for hover lerp, to avoid a huge jump after a stall (e.g. a GC pause). */
	private static final double HOVER_LERP_MAX_DT_SEC = 0.05d;
/** Peak alpha of the rarity-tinted glow drawn behind hovered/face-up cards and the sealed pack. */
	private static final float HOVER_RARITY_GLOW_ALPHA = 0.30f;
/** Inset, in pixels, of the sealed-pack rarity glow from the fitted pack art bounds. */
	private static final int PACK_SEALED_GLOW_INSET = 2;

	private final Client client;
	private final PackRevealService revealService;
	private final CardImageCacheService imageCacheService;
	private final PackCatalogService packCatalogService;
	private final PackRevealSoundService packRevealSoundService;
	private final TcgStateService tcgStateService;
	private final OsrsTcgConfig config;
/** Shared empty instance to avoid reallocating when the per-slot arrays reset to zero cards. */
	private static final boolean[] EMPTY_BOOL = new boolean[0];
/** Shared empty instance to avoid reallocating when the per-slot arrays reset to zero cards. */
	private static final SlotFaceCache[] EMPTY_SLOT_CACHE = new SlotFaceCache[0];
/** User's session-only zoom override (mouse wheel), NaN when none is set; written from the input thread, read during render. */
	private volatile double sessionPackZoomMultiplier = Double.NaN;
/** Zoom multiplier applied on the last render, used to detect a zoom change and invalidate cached face sizes. */
	private double lastAppliedZoomMul = Double.NaN;
/** Current eased hover-lift amount [0,1] for the sealed pack image. */
	private double packHoverLift;
/** Current eased hover-lift amount [0,1] per card slot. */
	private double[] cardHoverLift = new double[0];
/** Whether the last known pointer position came from the canvas hover listener rather than polling the client. */
	private volatile boolean revealHoverFromListener;
/** Last canvas X reported by the hover listener; written from the input thread, read during render. */
	private volatile int revealHoverCanvasX;
/** Last canvas Y reported by the hover listener; written from the input thread, read during render. */
	private volatile int revealHoverCanvasY;
/** Whether the pointer was inside the sealed apex-pack bounds on the previous frame, to detect hover-enter for the one-shot sound. */
	private boolean apexPackPointerWasInside;
/** Reusable [x, y] output buffer for {@link #revealPointer(int[])} to avoid allocating a Point every frame. */
	private final int[] pointerScratch = new int[2];
/** Whether the reveal's ambient sound was active on the previous frame, so it can be hard-stopped exactly once when the reveal ends. */
	private boolean packRevealSoundActivePrev;
/** {@link System#nanoTime()} of the previous hover-dynamics update, used to compute the frame delta time. */
	private long lastHoverDynamicsNanos;
/** Per-slot flag: whether that card's face image has finished prewarming into the shared render cache. */
	private boolean[] facePrewarmDone = EMPTY_BOOL;
/** Per-slot flag: whether a prewarm task for that card has already been submitted to the pool. */
	private boolean[] facePrewarmScheduled = EMPTY_BOOL;
/** Per-slot cache of the face draw request and its cache key inputs, to avoid rebuilding it every frame. */
	private SlotFaceCache[] slotFaceCache = EMPTY_SLOT_CACHE;
/** Identity string of the currently visible cards' names/art, used to detect when the reveal set changed and caches must be dropped. */
	private String lastVisibleFaceIdentity = "";
/** Index of the card the info tip currently targets, or -1 when no tip is showing. */
	private int tipCardIndex = -1;
/** {@link System#currentTimeMillis()} when hovering over {@link #tipCardIndex} began, used for the tip's delay/fade-in. */
	private long tipHoverStartedAtMs;
/** Last known cursor X, used to position the hover glow inside a pinned tip. */
	private int tipCursorX;
/** Last known cursor Y, used to position the hover glow inside a pinned tip. */
	private int tipCursorY;
/** Model content for the currently shown info tip, or null when none is showing. */
	private CardInfoTipModel.Content tipContent;
/** Whether the info tip is click-pinned open (persists regardless of hover) rather than hover-only. */
	private boolean tipPinned;
/** Whether {@link #tipPinnedPanelX}/{@link #tipPinnedPanelY} have been computed for the current pin. */
	private boolean tipPinBoundsReady;
/** Screen X of the pinned tip panel, computed once per pin. */
	private int tipPinnedPanelX;
/** Screen Y of the pinned tip panel, computed once per pin. */
	private int tipPinnedPanelY;
/** Canvas X the pinned tip was anchored to when pinned. */
	private int tipPinAnchorX;
/** Canvas Y the pinned tip was anchored to when pinned. */
	private int tipPinAnchorY;
/** Wiki page slug for the pinned card, or null if it has none. */
	private String tipPinnedWikiPage;
/** Cloud instance id of the pinned card, or null if it has none. */
	private String tipPinnedInstanceId;
/** Screen bounds of the currently drawn info tip panel, used for hit-testing and pin-dismiss distance checks. */
	private final Rectangle tipPanelBounds = new Rectangle();
/** Screen bounds of the reveal overlay's close button, used for hover styling and click hit-testing. */
	private final Rectangle closeButtonBounds = new Rectangle();
/** Screen bounds of each clickable action (inspect/wiki) inside the pinned tip, keyed by action id. */
	private final Map<String, Rectangle> tipActionBounds = new HashMap<>();
/** Extra padding, in pixels, around the pinned tip panel within which the cursor keeps it open. */
	private static final int TIP_PIN_DISMISS_PAD_PX = 48;
/** Wires the collaborators used to read reveal state, resolve art, and pin this overlay always-on-top above everything else. */
	@Inject
	public PackRevealOverlay(Client client, PackRevealService revealService, CardImageCacheService imageCacheService,
		PackCatalogService packCatalogService, PackRevealSoundService packRevealSoundService,
		TcgStateService tcgStateService, OsrsTcgConfig config)
	{
		this.client = client;
		this.revealService = revealService;
		this.imageCacheService = imageCacheService;
		this.packCatalogService = packCatalogService;
		this.packRevealSoundService = packRevealSoundService;
		this.tcgStateService = tcgStateService;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}
/**
	 * Called from the canvas mouse-listener thread with the latest hover position (or null when the
	 * pointer left the canvas / hover is unavailable), so render() can hit-test against a fresher
	 * position than polling the client each frame. Also dismisses a pinned info tip once the cursor
	 * moves far enough away from it.
	 */
	public void setRevealHoverCanvasPoint(Point canvasPoint)
	{
		if (canvasPoint == null)
		{
			revealHoverFromListener = false;
			clearCardInfoTip();
			return;
		}
		revealHoverCanvasX = canvasPoint.x;
		revealHoverCanvasY = canvasPoint.y;
		revealHoverFromListener = true;
		if (tipPinned && tipPinBoundsReady && !cursorNearPinnedTip(canvasPoint.x, canvasPoint.y))
		{
			clearCardInfoTip();
		}
	}
/**
	 * Paints one frame of the pack-reveal animation for whichever phase {@link PackRevealService} is
	 * currently in: dims the screen, draws the sealed pack / deal-out / flip-reveal cards with their
	 * hover and rarity-glow effects, and the close button and card info tooltip chrome. Returns null
	 * (and resets all animation state) when no reveal is active. Runs on the client's rendering thread.
	 */
	@Override
	public Dimension render(Graphics2D graphics)
	{
		Optional<PackRevealService.RevealPaintSnapshot> snapOpt = revealService.capturePaintFrame();
		if (snapOpt.isEmpty())
		{
			if (packRevealSoundActivePrev)
			{
				ForkJoinPool.commonPool().execute(() ->
				{
					try
					{
						packRevealSoundService.hardStop();
					}
					catch (Exception ignored)
					{
					}
				});
			}
			packRevealSoundActivePrev = false;
			persistPackZoomIfNeeded();
			resetHoverAnimations();
			closeButtonBounds.setBounds(0, 0, 0, 0);
			clearSlotCaches();
			return null;
		}
		PackRevealService.RevealPaintSnapshot snap = snapOpt.get();
		packRevealSoundActivePrev = true;

		Rectangle canvas = fullCanvas();
		PackRevealDrawUtil.drawDim(graphics, canvas);

		List<PackRevealService.RevealCard> cards = snap.getCards();
		int cardCount = cards.size();
		invalidateFaceSlotsIfChanged(cards);
		PackRevealService.Phase phase = snap.getPhase();
		PackRevealLayout.ViewportLayout layout = computeViewportLayout(canvas, cardCount, phase);
		if (phase != PackRevealService.Phase.PACK_READY)
		{
			apexPackPointerWasInside = false;
		}
		updateHoverDynamics(canvas, layout, cardCount, phase, snap.getPhaseElapsedMs());
		tryPlayMythicHum(phase, snap);
		tickDealCardMotionSounds(phase, cardCount, snap.getPhaseElapsedMs());
		if (phase == PackRevealService.Phase.PACK_READY)
		{
			Rectangle packBase = layout.packRect(canvas);
			Rectangle packScaled = packDrawRect(packBase);
			if (snap.isApexPackOpen())
			{
				boolean inPack = mouseInRect(packScaled);
				if (inPack && !apexPackPointerWasInside)
				{
					packRevealSoundService.playApexPackHoverOneShot();
				}
				apexPackPointerWasInside = inPack;
				if (config.packRarityHighlight())
				{
					float glowAlpha = (float) (HOVER_RARITY_GLOW_ALPHA * Math.max(0.22d, packHoverLift));
					Rectangle packGlowRect = PackRevealDrawUtil.uniformInset(
						PackRevealDrawUtil.fittedImageRect(packScaled, packArtForPackId(snap.getBoosterPackId())),
						PACK_SEALED_GLOW_INSET);
					PackRevealDrawUtil.drawGlow(graphics, packGlowRect, RarityMath.Tier.GODLY.getColor(), glowAlpha);
				}
			}
			else
			{
				apexPackPointerWasInside = false;
			}
			drawPackImage(graphics, packScaled, 1.0f, snap.getBoosterPackId());
			return finishEarlyPhase(graphics, canvas, snap);
		}

		if (phase == PackRevealService.Phase.PACK_FADING || phase == PackRevealService.Phase.AWAITING_PULLS)
		{
			if (cardCount > 0)
			{
				drawDealPhase(graphics, canvas, cards, layout, cardCount, 0L);
			}
			double progress = phase == PackRevealService.Phase.AWAITING_PULLS ? 1.0d : snap.getPackFadeProgress();
			Rectangle packBounds = layout.packRect(canvas);
			float packAlpha = (float) Math.max(0.0d, 1.0d - progress);
			if (packAlpha > 0.01f)
			{
				drawPackImage(graphics, packBounds, packAlpha, snap.getBoosterPackId());
			}
			return finishEarlyPhase(graphics, canvas, snap);
		}

		if (phase == PackRevealService.Phase.CARD_DEAL)
		{
			drawDealPhase(graphics, canvas, cards, layout, cardCount, snap.getPhaseElapsedMs());
			prewarmNextRevealFace(cards, PackRevealLayout.layoutCardSlots(canvas, cardCount, layout));
			return finishEarlyPhase(graphics, canvas, snap);
		}

		List<Rectangle> bounds = PackRevealLayout.layoutCardSlots(canvas, cardCount, layout);
		prewarmNextRevealFace(cards, bounds);
		List<Integer> drawOrder = new ArrayList<>(cards.size());
		for (int i = 0; i < cards.size(); i++)
		{
			drawOrder.add(i);
		}
		drawOrder.sort(Comparator.comparingDouble(i -> cardHoverLift[i]));
		for (int i : drawOrder)
		{
			PackRevealService.RevealCard card = cards.get(i);
			RevealCardVisual visual = revealCardVisual(i, bounds.get(i), snap);
			Rectangle r = visual.rect;
			boolean faceUp = visual.faceUp;
			double lift = visual.lift;
			float flipProgress = visual.flipProgress;
			float flipFade = (float) Math.abs(Math.cos(Math.toRadians(flipProgress * 180.0d)));
			float glowAlpha;
			if (flipProgress > 0f && flipProgress < 1f)
			{
				glowAlpha = HOVER_RARITY_GLOW_ALPHA * flipFade;
			}
			else if (faceUp)
			{
				glowAlpha = HOVER_RARITY_GLOW_ALPHA;
			}
			else
			{
				glowAlpha = (float) (HOVER_RARITY_GLOW_ALPHA * lift);
			}

			boolean showGlow = config.packRarityHighlight()
				|| faceUp
				|| (flipProgress > 0f && flipProgress < 1f);
			if (showGlow && glowAlpha > 0.01f)
			{
				Rectangle glowRect = r;
				if (flipProgress > 0f && flipProgress < 1f)
				{
					glowRect = PackRevealDrawUtil.scaleRectHorizontally(r, Math.max(0.04d, flipFade));
				}
				PackRevealDrawUtil.drawGlow(graphics, glowRect, card.getRarityColor(), glowAlpha);
			}
			drawFlippingCard(graphics, i, r, card, flipProgress);
			if (faceUp && flipProgress >= 1f)
			{
				if (card.isNew() && shouldShowNewBadge(card, revealService.getPreOwnedFoilNames()))
				{
					PackRevealDrawUtil.drawNewBadge(graphics, r);
				}
			}
			else if (!faceUp && config.packRarityText() && lift > 0.001d)
			{
				PackRevealDrawUtil.drawRarityLabel(graphics, r, card.getTier().getLabel(), card.getRarityColor(), (float) lift);
			}
		}

		updateCardInfoTip(cards, bounds, snap, canvas);
		paintRevealChrome(graphics, canvas, snap, cards);
		return null;
	}
/**
	 * Builds (or returns the cached) {@link CardFaceDrawRequest} for a card slot, keyed on size, art
	 * identity, wear-visibility config, and art path; also lazily creates the slot's foil/wear FX so
	 * they persist for the life of that reveal rather than being re-rolled every frame.
	 */
	private CardFaceDrawRequest cachedFaceRequest(int index, PackRevealService.RevealCard card, BufferedImage art,
		int width, int height)
	{
		ensureSlotFaceCache(index);
		SlotFaceCache slot = slotFaceCache[index];
		int artId = art == null ? 0 : System.identityHashCode(art);
		boolean wearWanted = config.showGradeWear();
		String artPath = artPathFor(card);
		if (slot.request != null
			&& slot.width == width
			&& slot.height == height
			&& slot.artId == artId
			&& slot.wearWanted == wearWanted
			&& Objects.equals(slot.artPath, artPath))
		{
			return slot.request;
		}

		PackCardResult pull = card.getPull();
		boolean foil = pull != null && pull.isFoil();
		boolean serverTier = pull != null && pull.hasServerTier();
		String seedName = seedNameFor(card);
		Long pulledAt = pull == null ? null : pull.getPulledAtEpochMs();
		String tierLabel = serverTier ? pull.getTierLabel() : card.getTier().getLabel();

		if (foil && slot.foilFx == null)
		{
			slot.foilFx = FoilFx.foilFxFromPulledAt(
				pulledAt,
				FoilFx.DEFAULT_SPARKLE_COUNT,
				seedName,
				tierLabel,
				card.getRarityColor());
		}
		if (wearWanted && pull != null && slot.wear == null)
		{
			slot.wear = WearFx.wearFxFromCondition(pull.getCondition(), pulledAt, false, seedName, pull.getPulledBy());
		}

		CardFaceDrawRequest req = CardFaceDrawRequest.builder()
			.card(card.getDefinition())
			.art(art)
			.artKey(artPath)
			.foil(foil)
			.rarityColor(card.getRarityColor())
			.tierLabel(tierLabel)
			.displayScore(serverTier ? Long.valueOf(pull.getScore()) : null)
			.useFoilAdjustedScore(foil && !serverTier)
			.wear(wearWanted ? slot.wear : null)
			.foilFx(foil ? slot.foilFx : null)
			.build();
		slot.request = req;
		slot.width = width;
		slot.height = height;
		slot.artId = artId;
		slot.wearWanted = wearWanted;
		slot.artPath = artPath;
		return req;
	}
/** Whether the "NEW" badge should be shown for a pulled card: always for foils/unnamed pulls, otherwise only if the player didn't already own that card as a foil. */
	private static boolean shouldShowNewBadge(PackRevealService.RevealCard card, Set<String> preOwnedFoilNames)
	{
		PackCardResult pull = card.getPull();
		if (pull == null || pull.isFoil())
		{
			return true;
		}
		String name = pull.getCardName();
		if (name == null || name.isBlank())
		{
			return true;
		}
		return !preOwnedFoilNames.contains(name.trim().toLowerCase(Locale.ROOT));
	}
/** Resolves the art path to draw for a card: the foil variant when this was a foil pull and one exists, otherwise the normal card image. */
	private static String artPathFor(PackRevealService.RevealCard card)
	{
		if (card == null)
		{
			return null;
		}
		CardDefinition def = card.getDefinition();
		boolean foilPull = card.getPull() != null && card.getPull().isFoil();
		String foilPath = def == null ? null : def.getFoilImagePath();
		if (foilPull && foilPath != null && !foilPath.isBlank())
		{
			return foilPath;
		}
		return def == null ? null : def.getImageUrl();
	}
/**
	 * Kicks off background rendering of up to two not-yet-cached card faces per frame (via
	 * {@link SharedCardRenderer}'s shared cache), so a card's flip animation finds its face already
	 * rendered instead of stalling the render thread the first time it's needed.
	 */
	private void prewarmNextRevealFace(List<PackRevealService.RevealCard> cards, List<Rectangle> slotBounds)
	{
		if (cards == null || slotBounds == null || cards.isEmpty() || slotBounds.size() < cards.size())
		{
			return;
		}
		ensureSlotFaceCache(cards.size() - 1);
		if (facePrewarmDone.length != cards.size())
		{
			facePrewarmDone = new boolean[cards.size()];
			facePrewarmScheduled = new boolean[cards.size()];
		}
		int budget = 2;
		for (int i = 0; i < cards.size() && budget > 0; i++)
		{
			PackRevealService.RevealCard card = cards.get(i);
			if (card == null || card.getPull() == null)
			{
				continue;
			}
			String name = card.getPull().getCardName();
			if (name == null || name.trim().isEmpty())
			{
				continue;
			}
			String artPath = artPathFor(card);
			ensureSlotFaceCache(i);
			SlotFaceCache slot = slotFaceCache[i];
			if (facePrewarmDone[i] && slot != null && Objects.equals(slot.artPath, artPath)
				&& SharedCardRenderer.isFaceCached(slot.width, slot.height, slot.request))
			{
				continue;
			}
			if (facePrewarmDone[i])
			{
				facePrewarmDone[i] = false;
				facePrewarmScheduled[i] = false;
			}
			boolean expectsArt = artPath != null && !artPath.isBlank();
			BufferedImage art = expectsArt ? imageCacheService.getCached(artPath) : null;
			if (expectsArt && art == null)
			{
				facePrewarmScheduled[i] = false;
				facePrewarmDone[i] = false;
				continue;
			}
			Rectangle slotBoundsAt = slotBounds.get(i);
			if (slotBoundsAt == null || slotBoundsAt.width < 4 || slotBoundsAt.height < 4)
			{
				continue;
			}
			CardFaceDrawRequest req = cachedFaceRequest(i, card, art, slotBoundsAt.width, slotBoundsAt.height);
			if (SharedCardRenderer.isFaceCached(slotBoundsAt.width, slotBoundsAt.height, req))
			{
				facePrewarmDone[i] = true;
				continue;
			}
			if (!facePrewarmScheduled[i])
			{
				scheduleFacePrewarm(i, slotBoundsAt.width, slotBoundsAt.height, req);
			}
			budget--;
		}
	}
/** Submits a one-shot background task to render and cache a card's face at the given size, marking the slot as scheduled so it isn't submitted twice. */
	private void scheduleFacePrewarm(int index, int width, int height, CardFaceDrawRequest req)
	{
		if (req == null || index < 0 || index >= facePrewarmScheduled.length || facePrewarmScheduled[index])
		{
			return;
		}
		facePrewarmScheduled[index] = true;
		ForkJoinPool.commonPool().execute(() -> SharedCardRenderer.prewarmFace(width, height, req));
	}
/** Deterministic seed string for a card's FX (foil sparkle / wear), derived from its pulled name, or empty if unknown. */
	private static String seedNameFor(PackRevealService.RevealCard card)
	{
		if (card.getPull() != null && card.getPull().getCardName() != null
			&& !card.getPull().getCardName().trim().isEmpty())
		{
			return card.getPull().getCardName().trim();
		}
		return "";
	}
/** Clears every slot's cached face request/FX and prewarm flags when the visible set of cards (names/art) has changed since the last frame. */
	private void invalidateFaceSlotsIfChanged(List<PackRevealService.RevealCard> cards)
	{
		String identity = visibleFaceIdentity(cards);
		if (identity.equals(lastVisibleFaceIdentity))
		{
			return;
		}
		lastVisibleFaceIdentity = identity;
		for (SlotFaceCache slot : slotFaceCache)
		{
			if (slot != null)
			{
				slot.wear = null;
				slot.foilFx = null;
				slot.request = null;
				slot.width = 0;
				slot.height = 0;
				slot.artId = 0;
				slot.artPath = null;
			}
		}
		if (facePrewarmDone.length > 0)
		{
			facePrewarmDone = new boolean[facePrewarmDone.length];
		}
		if (facePrewarmScheduled.length > 0)
		{
			facePrewarmScheduled = new boolean[facePrewarmScheduled.length];
		}
	}
/** Builds a cheap identity string (seed name + art path per slot) used to detect when the reveal's card set has changed. */
	private static String visibleFaceIdentity(List<PackRevealService.RevealCard> cards)
	{
		if (cards == null || cards.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(cards.size() * 24);
		for (int i = 0; i < cards.size(); i++)
		{
			if (i > 0)
			{
				sb.append(';');
			}
			PackRevealService.RevealCard card = cards.get(i);
			sb.append(seedNameFor(card)).append('|');
			String art = artPathFor(card);
			if (art != null)
			{
				sb.append(art);
			}
		}
		return sb.toString();
	}
/**
	 * Combines a card's flip progress with its hover-lift state into the rectangle, face-up flag, lift
	 * amount, and flip progress used to draw it: hover scaling only applies once a card has fully
	 * settled face-down (no flip in progress).
	 */
	private RevealCardVisual revealCardVisual(int index, Rectangle baseBounds, PackRevealService.RevealPaintSnapshot snap)
	{
		float flipProgress = snap.getFlipProgress(index);
		boolean faceUp = flipProgress >= 0.5f;
		boolean settledFaceDown = flipProgress <= 0f;
		double lift = settledFaceDown
			? ((index >= 0 && index < cardHoverLift.length) ? cardHoverLift[index] : 0.0d)
			: 0.0d;
		Rectangle r = baseBounds;
		if (settledFaceDown && lift > 0.0d)
		{
			double scale = 1.0d + (HOVER_CARD_SCALE - 1.0d) * lift;
			r = PackRevealDrawUtil.scaleRectCentered(r, scale);
		}
		return new RevealCardVisual(r, faceUp, lift, flipProgress);
	}
/** Grows {@link #slotFaceCache}, {@link #facePrewarmDone}, and {@link #facePrewarmScheduled} to cover {@code index} if needed, preserving existing entries. */
	private void ensureSlotFaceCache(int index)
	{
		int needed = index + 1;
		if (needed <= 0)
		{
			return;
		}
		if (slotFaceCache.length < needed)
		{
			SlotFaceCache[] next = new SlotFaceCache[needed];
			System.arraycopy(slotFaceCache, 0, next, 0, slotFaceCache.length);
			for (int i = slotFaceCache.length; i < needed; i++)
			{
				next[i] = new SlotFaceCache();
			}
			slotFaceCache = next;
		}
		if (facePrewarmDone.length < needed)
		{
			boolean[] nextDone = new boolean[needed];
			System.arraycopy(facePrewarmDone, 0, nextDone, 0, facePrewarmDone.length);
			facePrewarmDone = nextDone;
			boolean[] nextScheduled = new boolean[needed];
			System.arraycopy(facePrewarmScheduled, 0, nextScheduled, 0, facePrewarmScheduled.length);
			facePrewarmScheduled = nextScheduled;
		}
	}
/** Drops all per-slot face caches and prewarm flags back to the shared empty arrays; called once a reveal ends. */
	private void clearSlotCaches()
	{
		lastVisibleFaceIdentity = "";
		if (facePrewarmDone.length != 0)
		{
			facePrewarmDone = EMPTY_BOOL;
		}
		if (facePrewarmScheduled.length != 0)
		{
			facePrewarmScheduled = EMPTY_BOOL;
		}
		if (slotFaceCache.length != 0)
		{
			slotFaceCache = EMPTY_SLOT_CACHE;
		}
	}
/** Drops each slot's cached draw request/size (but keeps foil/wear FX) so faces re-render at the new size after a zoom change. */
	private void invalidateFaceSizes()
	{
		for (SlotFaceCache slot : slotFaceCache)
		{
			if (slot != null)
			{
				slot.request = null;
				slot.width = 0;
				slot.height = 0;
			}
		}
		if (facePrewarmDone.length > 0)
		{
			facePrewarmDone = new boolean[facePrewarmDone.length];
		}
		if (facePrewarmScheduled.length > 0)
		{
			facePrewarmScheduled = new boolean[facePrewarmScheduled.length];
		}
	}
/**
	 * Draws a card mid-flip by horizontally scaling it (a cheap pseudo-3D flip) from flat card-back
	 * (0deg) through edge-on to flat card-face (180deg), switching artwork at the 90-degree halfway
	 * point. Falls back to the card back if the face isn't cached yet, scheduling a prewarm for it.
	 */
	private void drawFlippingCard(Graphics2D graphics, int index, Rectangle r, PackRevealService.RevealCard card,
		float flipProgress)
	{
		float progress = Math.max(0f, Math.min(1f, flipProgress));
		double angleDeg = progress * 180.0d;
		double scaleX = Math.max(0.04d, Math.abs(Math.cos(Math.toRadians(angleDeg))));
		boolean showFront = angleDeg >= 90.0d;

		Graphics2D g2 = (Graphics2D) graphics.create();
		try
		{
			double cx = r.getCenterX();
			double cy = r.getCenterY();
			AffineTransform at = g2.getTransform();
			at.translate(cx, cy);
			at.scale(scaleX, 1.0d);
			at.translate(-cx, -cy);
			g2.setTransform(at);

			if (showFront)
			{
				String artPath = artPathFor(card);
				boolean expectsArt = artPath != null && !artPath.isBlank();
				BufferedImage linked = expectsArt ? imageCacheService.getCached(artPath) : null;
				if (expectsArt && linked == null)
				{
					drawRevealCardBack(g2, r, card);
				}
				else
				{
					CardFaceDrawRequest req = cachedFaceRequest(index, card, linked, r.width, r.height);
					if (!SharedCardRenderer.drawCardFaceIfCached(g2, r, req))
					{
						drawRevealCardBack(g2, r, card);
						scheduleFacePrewarm(index, r.width, r.height, req);
					}
				}
			}
			else
			{
				drawRevealCardBack(g2, r, card);
			}
		}
		finally
		{
			g2.dispose();
		}
	}
/** Resolved per-frame visual state for one card slot: its drawn rectangle, whether it's showing its face, hover lift, and flip progress. */
	private static final class RevealCardVisual
	{
		private final Rectangle rect;
		private final boolean faceUp;
		private final double lift;
		private final float flipProgress;
/** Stores the resolved fields verbatim. */
		private RevealCardVisual(Rectangle rect, boolean faceUp, double lift, float flipProgress)
		{
			this.rect = rect;
			this.faceUp = faceUp;
			this.lift = lift;
			this.flipProgress = flipProgress;
		}
	}
/**
	 * Current on-screen bounds of the sealed pack image (including hover scale), or null when no
	 * reveal is active or it isn't in the {@code PACK_READY} phase. Callable from outside the render
	 * thread; synchronizes on {@link #revealService} to read a consistent phase/card snapshot.
	 */
	public Rectangle currentPackBounds()
	{
		synchronized (revealService)
		{
			if (!revealService.isActive() || revealService.getPhase() != PackRevealService.Phase.PACK_READY)
			{
				return null;
			}
			Rectangle canvas = fullCanvas();
			int n = revealService.getCards().size();
			PackRevealLayout.ViewportLayout layout = computeViewportLayout(canvas, n, revealService.getPhase());
			Rectangle packBase = layout.packRect(canvas);
			return packDrawRect(packBase);
		}
	}
/**
	 * Current on-screen bounds of each card slot (including hover scale), or an empty list outside the
	 * card-reveal/wait-close phases. Synchronizes on {@link #revealService} for a consistent snapshot.
	 */
	public List<Rectangle> currentCardBounds()
	{
		synchronized (revealService)
		{
			PackRevealService.Phase phase = revealService.getPhase();
			if (!revealService.isActive() || phase == PackRevealService.Phase.PACK_READY
				|| phase == PackRevealService.Phase.PACK_FADING
				|| phase == PackRevealService.Phase.AWAITING_PULLS
				|| phase == PackRevealService.Phase.CARD_DEAL)
			{
				return List.of();
			}
			Rectangle canvas = fullCanvas();
			int n = revealService.getCards().size();
			List<Rectangle> bases = PackRevealLayout.layoutCardSlots(canvas, n, computeViewportLayout(canvas, n, phase));
			return withCardHoverVisualScale(bases);
		}
	}
/** Index of the face-up (already-revealed) card slot under the given canvas point, or -1 if none. */
	private int faceUpCardIndexAt(Point canvasPoint)
	{
		if (canvasPoint == null)
		{
			return -1;
		}
		List<Rectangle> bounds = currentCardBounds();
		if (bounds.isEmpty())
		{
			return -1;
		}
		for (int i = 0; i < bounds.size(); i++)
		{
			Rectangle r = bounds.get(i);
			if (r != null && r.contains(canvasPoint) && revealService.isCardRevealed(i))
			{
				return i;
			}
		}
		return -1;
	}
/** Shared tail for phases that don't show individual cards yet (pack ready/fading/dealing): paints the close button, clears the info tip, and returns null. */
	private Dimension finishEarlyPhase(Graphics2D graphics, Rectangle canvas, PackRevealService.RevealPaintSnapshot snap)
	{
		paintRevealChrome(graphics, canvas, snap, null);
		clearCardInfoTip();
		return null;
	}
/** Draws the face-down card back for a slot, foil-styled when the underlying pull is a foil. */
	private void drawRevealCardBack(Graphics2D g2, Rectangle r, PackRevealService.RevealCard card)
	{
		SharedCardRenderer.drawCardBack(g2, r, card.getPull().isFoil(), cardBackImage());
	}
/** Draws the overlay chrome common to every phase: the close button (hover-styled) and, when cards are showing, the card info tooltip. */
	private void paintRevealChrome(Graphics2D graphics, Rectangle canvas, PackRevealService.RevealPaintSnapshot snap,
		List<PackRevealService.RevealCard> cards)
	{
		PackRevealDrawUtil.layoutCloseButton(canvas, closeButtonBounds);
		boolean hover = false;
		if (revealPointer(pointerScratch))
		{
			Point p = new Point(pointerScratch[0], pointerScratch[1]);
			hover = closeButtonBounds.contains(p) && !cardInfoTipCoversPoint(p);
		}
		PackRevealDrawUtil.drawCloseButton(graphics, closeButtonBounds, hover);
		if (cards != null)
		{
			paintCardInfoTip(graphics, canvas, cards);
		}
	}
/** Whether a click at the given canvas point should close the reveal: inside the close button and not intercepted by the info tip. */
	public boolean handleCloseButtonClick(Point canvasPoint)
	{
		return canvasPoint != null && closeButtonBounds.width > 0
			&& closeButtonBounds.contains(canvasPoint) && !cardInfoTipCoversPoint(canvasPoint);
	}
/** Whether the given point falls within the currently visible (delay-elapsed or pinned) card info tip panel, so clicks/hover under it don't fall through to what's behind it. */
	private boolean cardInfoTipCoversPoint(Point p)
	{
		if (tipContent == null || tipPanelBounds.width <= 0)
		{
			return false;
		}
		long elapsed = System.currentTimeMillis() - tipHoverStartedAtMs;
		if (!tipPinned && elapsed < CardInfoTipModel.DELAY_MS)
		{
			return false;
		}
		return tipPanelBounds.contains(p.x, p.y);
	}
/** Index of the first rectangle in the list containing the current pointer position, or -1 if none / pointer unavailable. */
	private int indexOfRectUnderMouse(List<Rectangle> rects)
	{
		if (!revealPointer(pointerScratch))
		{
			return -1;
		}
		int mx = pointerScratch[0];
		int my = pointerScratch[1];
		for (int i = 0; i < rects.size(); i++)
		{
			if (rects.get(i).contains(mx, my))
			{
				return i;
			}
		}
		return -1;
	}
/** Whether the current pointer position falls inside the given rectangle. */
	private boolean mouseInRect(Rectangle r)
	{
		if (r == null)
		{
			return false;
		}
		return revealPointer(pointerScratch) && r.contains(pointerScratch[0], pointerScratch[1]);
	}
/** Saves the session zoom override back to persisted state if the player nudged it (mouse wheel) during this reveal. */
	private void persistPackZoomIfNeeded()
	{
		if (!Double.isNaN(sessionPackZoomMultiplier))
		{
			tcgStateService.setPackRevealOverlayScale(sessionPackZoomMultiplier);
		}
	}
/** Resets all hover/zoom/tip animation state to its idle defaults; called when a reveal ends or leaves a recognized phase. */
	private void resetHoverAnimations()
	{
		packHoverLift = 0.0d;
		cardHoverLift = new double[0];
		sessionPackZoomMultiplier = Double.NaN;
		lastAppliedZoomMul = Double.NaN;
		revealHoverFromListener = false;
		apexPackPointerWasInside = false;
		lastHoverDynamicsNanos = 0L;
		clearCardInfoTip();
	}
/** Clears the card info tooltip's state entirely, whether it was hover-shown or pinned. */
	private void clearCardInfoTip()
	{
		tipCardIndex = -1;
		tipHoverStartedAtMs = 0L;
		tipContent = null;
		tipPinned = false;
		tipPinBoundsReady = false;
		tipPinnedWikiPage = null;
		tipPinnedInstanceId = null;
		tipPanelBounds.setBounds(0, 0, 0, 0);
		tipActionBounds.clear();
	}
/**
	 * Click-pins the card info tip for the face-up card at the given canvas point, so it stays open
	 * without hover. Fails (returns false) if there's no face-up card there or it has neither a wiki
	 * page nor an inspectable instance id. Synchronizes on {@link #revealService} to read the card.
	 */
	public boolean pinCardInfoTipAt(Point canvasPoint)
	{
		if (canvasPoint == null)
		{
			return false;
		}
		PackRevealService.RevealCard card;
		int index;
		synchronized (revealService)
		{
			index = faceUpCardIndexAt(canvasPoint);
			if (index < 0)
			{
				return false;
			}
			card = revealService.getCards().get(index);
		}
		String wikiPage = CardInfoTipModel.wikiPageFor(card);
		String instanceId = CardInfoTipModel.instanceIdFor(card);
		if (wikiPage == null && instanceId == null)
		{
			return false;
		}
		tipCardIndex = index;
		tipContent = CardInfoTipModel.forPackRevealCard(card, true);
		tipPinned = true;
		tipPinBoundsReady = false;
		tipPinnedWikiPage = wikiPage;
		tipPinnedInstanceId = instanceId;
		tipPinAnchorX = canvasPoint.x;
		tipPinAnchorY = canvasPoint.y;
		tipCursorX = canvasPoint.x;
		tipCursorY = canvasPoint.y;
		tipHoverStartedAtMs = System.currentTimeMillis() - CardInfoTipModel.DELAY_MS - CardInfoTipModel.FADE_IN_MS;
		tipActionBounds.clear();
		tipPanelBounds.setBounds(0, 0, 0, 0);
		return true;
	}
/** Whether the card info tip is currently pinned open. */
	public boolean isCardInfoTipPinned()
	{
		return tipPinned;
	}
/**
	 * Handles a click while the tip is pinned: opens the inspect page or wiki page if the click landed
	 * on that action, and always clears the pin afterward. Returns whether the click was consumed by
	 * the tip (an action, or anywhere else inside its bounds) rather than falling through.
	 */
	public boolean handlePinnedTipClick(Point canvasPoint)
	{
		if (!tipPinned || canvasPoint == null)
		{
			return false;
		}
		Rectangle inspectHit = tipActionBounds.get(CardInfoTipModel.ACTION_INSPECT);
		boolean onInspect = inspectHit != null && inspectHit.contains(canvasPoint);
		Rectangle wikiHit = tipActionBounds.get(CardInfoTipModel.ACTION_OPEN_WIKI);
		boolean onWiki = wikiHit != null && wikiHit.contains(canvasPoint);
		boolean onTip = tipPinBoundsReady && tipPanelBounds.contains(canvasPoint);
		String instanceId = tipPinnedInstanceId;
		String wikiPage = tipPinnedWikiPage;
		clearCardInfoTip();
		if (onInspect && instanceId != null)
		{
			LinkBrowser.browse(CloudEndpoints.webUrl("/inspect/" + instanceId));
			return true;
		}
		if (onWiki && wikiPage != null)
		{
			String url = OsrsWiki.url(wikiPage);
			if (url != null)
			{
				LinkBrowser.browse(url);
			}
			return true;
		}
		return onTip;
	}
/**
	 * Updates which card (if any) the hover info tip targets: dismisses a pinned tip once the cursor
	 * strays too far, otherwise finds the fully-flipped face-up card under the cursor and starts its
	 * hover timer when it changes.
	 */
	private void updateCardInfoTip(List<PackRevealService.RevealCard> cards, List<Rectangle> bases,
		PackRevealService.RevealPaintSnapshot snap, Rectangle canvas)
	{
		if (cards == null || bases == null || snap == null || canvas == null || cards.isEmpty())
		{
			clearCardInfoTip();
			return;
		}
		if (!revealPointer(pointerScratch))
		{
			clearCardInfoTip();
			return;
		}
		int mx = pointerScratch[0];
		int my = pointerScratch[1];
		tipCursorX = mx;
		tipCursorY = my;

		if (tipPinned)
		{
			if (tipPinBoundsReady && !cursorNearPinnedTip(mx, my))
			{
				clearCardInfoTip();
			}
			return;
		}

		int hi = -1;
		for (int i = 0; i < cards.size() && i < bases.size(); i++)
		{
			RevealCardVisual visual = revealCardVisual(i, bases.get(i), snap);
			if (visual.flipProgress < 1f || !visual.faceUp)
			{
				continue;
			}
			if (visual.rect.contains(mx, my))
			{
				hi = i;
			}
		}
		if (hi < 0)
		{
			clearCardInfoTip();
			return;
		}
		if (hi != tipCardIndex)
		{
			tipCardIndex = hi;
			tipHoverStartedAtMs = System.currentTimeMillis();
			tipContent = CardInfoTipModel.forPackRevealCard(cards.get(hi));
		}
	}
/** Whether the given point is within {@link #TIP_PIN_DISMISS_PAD_PX} of the pinned tip's panel bounds. */
	private boolean cursorNearPinnedTip(int mx, int my)
	{
		int pad = TIP_PIN_DISMISS_PAD_PX;
		return mx >= tipPanelBounds.x - pad
			&& my >= tipPanelBounds.y - pad
			&& mx < tipPanelBounds.x + tipPanelBounds.width + pad
			&& my < tipPanelBounds.y + tipPanelBounds.height + pad;
	}
/**
	 * Paints the card info tip if one should be visible: full opacity and anchored near the pinning
	 * click when pinned, or fading/sliding in near the top-right once the hover delay has elapsed.
	 * Records the panel's screen bounds and (when pinned) its action hitboxes for hit-testing.
	 */
	private void paintCardInfoTip(Graphics2D graphics, Rectangle canvas, List<PackRevealService.RevealCard> cards)
	{
		if (tipContent == null || tipCardIndex < 0 || tipCardIndex >= cards.size())
		{
			return;
		}
		long elapsed = System.currentTimeMillis() - tipHoverStartedAtMs;
		if (!tipPinned && elapsed < CardInfoTipModel.DELAY_MS)
		{
			return;
		}
		float alpha;
		float yOffset;
		if (tipPinned)
		{
			alpha = 1f;
			yOffset = 0f;
		}
		else
		{
			float fadeT = Math.min(1f, (elapsed - CardInfoTipModel.DELAY_MS) / (float) CardInfoTipModel.FADE_IN_MS);
			float eased = 1f - (1f - fadeT) * (1f - fadeT);
			alpha = eased;
			yOffset = 4f * (1f - eased);
		}

		Dimension size = CardInfoTipPainter.measure(graphics, tipContent);
		int drawX;
		int drawY;
		if (tipPinned)
		{
			if (!tipPinBoundsReady)
			{
				Point pos = CardInfoTipModel.position(
					tipPinAnchorX, tipPinAnchorY, size.width, size.height, canvas.width, canvas.height);
				tipPinnedPanelX = pos.x;
				tipPinnedPanelY = pos.y;
				tipPinBoundsReady = true;
			}
			drawX = tipPinnedPanelX;
			drawY = tipPinnedPanelY;
		}
		else
		{
			Point pos = CardInfoTipModel.topRight(size.width, size.height, canvas.width, canvas.height);
			drawX = pos.x;
			drawY = pos.y;
		}
		tipPanelBounds.setBounds(drawX, drawY + Math.round(yOffset), size.width, size.height);
		Color titleColor = CardColorMath.brighterColor(cards.get(tipCardIndex).getRarityColor());
		Integer hoverX = tipPinned ? tipCursorX : null;
		Integer hoverY = tipPinned ? tipCursorY : null;
		Map<String, Rectangle> actionOut = tipPinned ? tipActionBounds : null;
		CardInfoTipPainter.paint(graphics, drawX, drawY, tipContent, titleColor, alpha, yOffset,
			hoverX, hoverY, actionOut);
	}
/**
	 * Writes the current pointer's canvas coordinates into {@code outXY} and returns true, preferring
	 * the last position reported by the hover listener over polling {@link Client#getMouseCanvasPosition()}.
	 */
	private boolean revealPointer(int[] outXY)
	{
		if (revealHoverFromListener)
		{
			outXY[0] = revealHoverCanvasX;
			outXY[1] = revealHoverCanvasY;
			return true;
		}
		net.runelite.api.Point mp = client.getMouseCanvasPosition();
		if (mp == null)
		{
			return false;
		}
		outXY[0] = mp.getX();
		outXY[1] = mp.getY();
		return true;
	}
/** Starts/stops the mythic-pull ambient hum: wanted whenever there's an unrevealed mythic card and the pack isn't still sealed. */
	private void tryPlayMythicHum(PackRevealService.Phase phase, PackRevealService.RevealPaintSnapshot snap)
	{
		boolean humWanted = phase != PackRevealService.Phase.PACK_READY && snap.hasUnrevealedMythic();
		packRevealSoundService.tryPlayMythicHum(humWanted);
	}
/** Drives the per-card "whoosh" motion sounds during the deal phase, and stops them outside it. */
	private void tickDealCardMotionSounds(PackRevealService.Phase phase, int cardCount, long phaseElapsedMs)
	{
		if (phase == PackRevealService.Phase.CARD_DEAL && cardCount > 0)
		{
			packRevealSoundService.tickDealMotionSounds(true, phaseElapsedMs, cardCount,
				PackRevealService.PACK_DEAL_STAGGER_MS);
		}
		else
		{
			packRevealSoundService.tickDealMotionSounds(false, 0L, 0, 0L);
		}
	}
/**
	 * Advances the eased hover-lift values ({@link #packHoverLift}, {@link #cardHoverLift}) one frame
	 * toward their per-phase targets: the sealed pack lifts on hover in {@code PACK_READY}; cards lift
	 * on hover once dealt and only while still face-down. Falls back to a full reset for any
	 * unrecognized phase.
	 */
	private void updateHoverDynamics(Rectangle canvas, PackRevealLayout.ViewportLayout layout, int cardCount,
		PackRevealService.Phase phase, long phaseElapsedMs)
	{
		double lerp = advanceHoverLerpFactor();

		if (phase == PackRevealService.Phase.PACK_READY)
		{
			Rectangle packBase = layout.packRect(canvas);
			double target = mouseInRect(packDrawRect(packBase)) ? 1.0d : 0.0d;
			packHoverLift = stepToward(packHoverLift, target, lerp);
			decayAllCardHovers(lerp);
			return;
		}

		if (phase == PackRevealService.Phase.PACK_FADING || phase == PackRevealService.Phase.AWAITING_PULLS)
		{
			packHoverLift = stepToward(packHoverLift, 0.0d, lerp);
			decayAllCardHovers(lerp);
			return;
		}

		if (phase == PackRevealService.Phase.CARD_DEAL)
		{
			packHoverLift = stepToward(packHoverLift, 0.0d, lerp);
			ensureCardHoverLength(cardCount);
			List<Rectangle> bases = PackRevealDealLayout.layoutDealPhaseCardRects(canvas, layout, cardCount, phaseElapsedMs);
			int hi = indexOfRectUnderMouse(bases);
			for (int i = 0; i < cardHoverLift.length; i++)
			{
				double target = (i == hi) ? 1.0d : 0.0d;
				cardHoverLift[i] = stepToward(cardHoverLift[i], target, lerp);
			}
			return;
		}

		if (phase == PackRevealService.Phase.CARD_REVEAL || phase == PackRevealService.Phase.WAIT_CLOSE)
		{
			packHoverLift = stepToward(packHoverLift, 0.0d, lerp);
			ensureCardHoverLength(cardCount);
			List<Rectangle> bases = PackRevealLayout.layoutCardSlots(canvas, cardCount, layout);
			int hi = indexOfRectUnderMouse(withCardHoverVisualScale(bases));
			for (int i = 0; i < cardHoverLift.length; i++)
			{
				boolean faceUp = revealService.isCardRevealed(i);
				double target = (!faceUp && i == hi) ? 1.0d : 0.0d;
				cardHoverLift[i] = stepToward(cardHoverLift[i], target, lerp);
			}
			return;
		}

		resetHoverAnimations();
	}
/** Frame-rate-independent lerp factor for this frame, derived from wall-clock delta since the last call so hover animation speed doesn't vary with FPS. */
	private double advanceHoverLerpFactor()
	{
		long now = System.nanoTime();
		if (lastHoverDynamicsNanos == 0L)
		{
			lastHoverDynamicsNanos = now;
			return HOVER_LERP;
		}
		double dt = (now - lastHoverDynamicsNanos) / 1_000_000_000.0;
		lastHoverDynamicsNanos = now;
		dt = Math.max(0.0d, Math.min(HOVER_LERP_MAX_DT_SEC, dt));
		return 1.0d - Math.pow(1.0d - HOVER_LERP, dt * HOVER_LERP_REFERENCE_HZ);
	}
/** One exponential-decay step of {@code current} toward {@code target} by {@code factor}. */
	private static double stepToward(double current, double target, double factor)
	{
		return current + (target - current) * factor;
	}
/** Applies the sealed pack's current hover-scale to its base (unscaled) rectangle. */
	private Rectangle packDrawRect(Rectangle packBase)
	{
		double scale = 1.0d + (PACK_IMAGE_HOVER_MAX_SCALE - 1.0d) * packHoverLift;
		return PackRevealDrawUtil.scaleRectCentered(packBase, scale);
	}
/** Applies each card's current hover-scale to its base slot rectangle; already-revealed cards are left at base size. */
	private List<Rectangle> withCardHoverVisualScale(List<Rectangle> bases)
	{
		List<Rectangle> out = new ArrayList<>(bases.size());
		for (int i = 0; i < bases.size(); i++)
		{
			if (revealService.isCardRevealed(i))
			{
				out.add(bases.get(i));
				continue;
			}
			double lift = (i < cardHoverLift.length) ? cardHoverLift[i] : 0.0d;
			double scale = 1.0d + (HOVER_CARD_SCALE - 1.0d) * lift;
			out.add(PackRevealDrawUtil.scaleRectCentered(bases.get(i), scale));
		}
		return out;
	}
/** The zoom multiplier to lay out with: the session override if the player has nudged it this reveal, otherwise the clamped persisted value. */
	private double preferredZoomMultiplier()
	{
		if (!Double.isNaN(sessionPackZoomMultiplier))
		{
			return sessionPackZoomMultiplier;
		}
		return PackRevealZoomUtil.clamp(tcgStateService.getState().getPackRevealOverlayScale());
	}
/**
	 * Applies one mouse-wheel step to the pack reveal zoom, persists the new value immediately, and
	 * invalidates cached card face sizes so they re-render at the new scale.
	 */
	public void nudgeSessionPackZoom(int wheelRotation)
	{
		if (wheelRotation == 0)
		{
			return;
		}
		double base = preferredZoomMultiplier();
		sessionPackZoomMultiplier = PackRevealZoomUtil.nudge(base, wheelRotation);
		tcgStateService.setPackRevealOverlayScale(sessionPackZoomMultiplier);
		invalidateFaceSizes();
	}
/** Resizes {@link #cardHoverLift} to exactly {@code n} entries (clamped to non-negative) if it isn't already that length, resetting lift to zero. */
	private void ensureCardHoverLength(int n)
	{
		if (n < 0)
		{
			n = 0;
		}
		if (cardHoverLift == null || cardHoverLift.length != n)
		{
			cardHoverLift = new double[n];
		}
	}
/** Steps every card's hover lift toward zero, used in phases where cards can't be individually hovered. */
	private void decayAllCardHovers(double lerpFactor)
	{
		if (cardHoverLift == null || cardHoverLift.length == 0)
		{
			return;
		}
		for (int i = 0; i < cardHoverLift.length; i++)
		{
			cardHoverLift[i] = stepToward(cardHoverLift[i], 0.0d, lerpFactor);
		}
	}
/** Computes the viewport layout for the current canvas/card count/phase at the preferred zoom, reporting the applied zoom back via {@link #noteAppliedZoom}. */
	private PackRevealLayout.ViewportLayout computeViewportLayout(Rectangle canvas, int cardCount, PackRevealService.Phase phase)
	{
		return PackRevealLayout.computeViewportLayout(canvas, cardCount, phase, preferredZoomMultiplier(), this::noteAppliedZoom);
	}
/** The full RuneLite game canvas as a rectangle at the origin. */
	private Rectangle fullCanvas()
	{
		return new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
	}
/** Callback from the layout computation: invalidates cached face sizes when the actually-applied zoom differs from last frame's. */
	private void noteAppliedZoom(double zoomMul)
	{
		if (Double.compare(lastAppliedZoomMul, zoomMul) != 0)
		{
			lastAppliedZoomMul = zoomMul;
			invalidateFaceSizes();
		}
	}
/**
	 * Draws every card as a face-down back during the deal animation, in {@link PackRevealDealLayout}'s
	 * layer order (waiting pile, then in-flight cards on top, then arrived cards) so flying cards
	 * correctly occlude the ones still stacked or already placed.
	 */
	private void drawDealPhase(Graphics2D graphics, Rectangle canvas, List<PackRevealService.RevealCard> cards,
		PackRevealLayout.ViewportLayout layout, int cardCount, long phaseElapsedMs)
	{
		long stagger = PackRevealService.PACK_DEAL_STAGGER_MS;
		long flight = PackRevealService.PACK_DEAL_FLIGHT_MS;
		List<Rectangle> rects = PackRevealDealLayout.layoutDealPhaseCardRects(canvas, layout, cardCount, phaseElapsedMs);

		List<Integer> order = new ArrayList<>(cardCount);
		for (int i = 0; i < cardCount; i++)
		{
			order.add(i);
		}
		order.sort(Comparator
			.comparingInt((Integer i) -> PackRevealDealLayout.dealDrawLayer(phaseElapsedMs, i, stagger, flight))
			.thenComparingInt(i -> i));

		for (int i : order)
		{
			PackRevealService.RevealCard card = cards.get(i);
			Rectangle r = rects.get(i);
			SharedCardRenderer.drawCardBack(graphics, r, card.getPull().isFoil(),
				cardBackImage());
		}
	}
/** Draws the sealed pack's art (or a generic card back if the booster has none) at the given alpha, for the sealed and fade-out phases. */
	private void drawPackImage(Graphics2D g, Rectangle bounds, float alpha, String boosterPackId)
	{
		BufferedImage packArt = packArtForPackId(boosterPackId);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
		if (packArt != null)
		{
			PackRevealDrawUtil.drawImageFit(g, packArt, bounds);
		}
		else
		{
			SharedCardRenderer.drawCardBack(g, bounds, false, cardBackImage());
		}
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
	}
/** The shared generic card-back image, from the image cache. */
	private BufferedImage cardBackImage()
	{
		return imageCacheService.getCached(SharedCardRenderer.CARD_BACK_PATH);
	}
/** Resolves a booster's reveal-sleeve art from the catalog cache, or null if the booster/path/image is unknown. */
	private BufferedImage packArtForPackId(String boosterPackId)
	{
		if (boosterPackId == null || boosterPackId.isBlank())
		{
			return null;
		}
		BoosterPackDefinition pack = packCatalogService.getCache().get(boosterPackId).orElse(null);
		String imagePath = pack == null ? null : pack.revealSleevePath();
		if (imagePath == null)
		{
			return null;
		}
		return imageCacheService.getCached(imagePath);
	}
/** Per-card-slot cache of the built {@link CardFaceDrawRequest} plus the FX and size/identity keys it was built from, to avoid recomputing every frame. */
	private static final class SlotFaceCache
	{
		private WearFx wear;
		private FoilFx foilFx;
		private CardFaceDrawRequest request;
		private int width;
		private int height;
		private int artId;
		private boolean wearWanted;
		private String artPath;
	}
}

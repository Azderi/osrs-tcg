package com.osrstcg.overlay;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.cloud.api.CloudEndpoints;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.catalog.PackImageUrls;
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

@Singleton
public class PackRevealOverlay extends Overlay
{
	/** Hovered revealed card scales up slightly (centered). */
	private static final double HOVER_CARD_SCALE = 1.072d;
	/** Sealed pack grows slightly when hovered (PACK_READY). */
	private static final double PACK_IMAGE_HOVER_MAX_SCALE = 1.085d;
	/** Per-step lerp at {@value #HOVER_LERP_REFERENCE_HZ} Hz; converted to wall-clock in {@link #advanceHoverLerpFactor()}. */
	private static final double HOVER_LERP = 0.22d;
	private static final double HOVER_LERP_REFERENCE_HZ = 60.0d;
	private static final double HOVER_LERP_MAX_DT_SEC = 0.05d;
	/** Clamped when applied so the layout never collapses or overflows wildly. */
	static final double PACK_REVEAL_ZOOM_MIN = PackRevealZoomUtil.MIN;
	static final double PACK_REVEAL_ZOOM_MAX = PackRevealZoomUtil.MAX;
	/** Max rarity glow alpha when a card is fully hovered (no glow when not hovered). */
	private static final float HOVER_RARITY_GLOW_ALPHA = 0.30f;
	/** Tucks the apex sealed-pack glow slightly inside the letterboxed art rect (px per edge). */
	private static final int PACK_SEALED_GLOW_INSET = 2;

	private final Client client;
	private final PackRevealService revealService;
	private final CardImageCacheService imageCacheService;
	private final PackCatalogService packCatalogService;
	private final PackRevealSoundService packRevealSoundService;
	private final TcgStateService tcgStateService;
	private final OsrsTcgConfig config;

	private static final boolean[] EMPTY_BOOL = new boolean[0];
	private static final SlotFaceCache[] EMPTY_SLOT_CACHE = new SlotFaceCache[0];

	/**
	 * Preferred zoom for this reveal ({@link Double#NaN} = auto: largest of 1×/2× that fits).
	 * Wheel sets an explicit preference; applied size is still capped by what fits the canvas.
	 */
	private volatile double sessionPackZoomMultiplier = Double.NaN;
	/** Last applied zoom mul - used to invalidate face caches when auto zoom switches. */
	private double lastAppliedZoomMul = Double.NaN;

	/** 0 = base size, 1 = full {@link #PACK_IMAGE_HOVER_MAX_SCALE} for sealed pack. */
	private double packHoverLift;
	/** Per-card 0 = base, 1 = full {@link #HOVER_CARD_SCALE} during reveal. */
	private double[] cardHoverLift = new double[0];
	/**
	 * Canvas-space pointer from {@link PackRevealInputListener} (same as {@link java.awt.event.MouseEvent#getPoint()}
	 * for clicks). {@link Client#getMouseCanvasPosition()} can disagree with that space while the reveal input listener
	 * is consuming mouse events, so hover hit-testing prefers this when set.
	 */
	private volatile boolean revealHoverFromListener;
	private volatile int revealHoverCanvasX;
	private volatile int revealHoverCanvasY;
	/** Rising edge for {@link PackRevealSoundService#playApexPackHoverOneShot()} (sealed apex pack). */
	private boolean apexPackPointerWasInside;
	/** Single-threaded client thread scratch for pointer reads (avoid per-frame allocations). */
	private final int[] pointerScratch = new int[2];
	/** Used to call {@link PackRevealSoundService#hardStop()} only on active→inactive transition. */
	private boolean packRevealSoundActiveLastFrame;
	/** Wall-clock source for time-based hover lerp. */
	private long lastHoverDynamicsNanos;

	/**
	 * Per-slot face raster prewarm during deal/reveal.
	 */
	private boolean[] facePrewarmDone = EMPTY_BOOL;
	private boolean[] facePrewarmScheduled = EMPTY_BOOL;
	/** Reused FoilFx / WearFx / CardFaceDrawRequest per reveal slot. */
	private SlotFaceCache[] slotFaceCache = EMPTY_SLOT_CACHE;
	/**
	 * Identity of the currently painted visible batch (seed names + art paths). When the next deal page
	 * or placeholder→pull swap changes cards in the same slot indices, face prewarm / slot FX must reset
	 * or flips keep showing card backs from a stale scheduled bake.
	 */
	private String lastVisibleFaceIdentity = "";

	/** Face-up pack card under cursor for {@link CardInfoTipModel} (−1 = none). */
	private int tipCardIndex = -1;
	private long tipHoverStartedAtMs;
	private int tipCursorX;
	private int tipCursorY;
	private CardInfoTipModel.Content tipContent;
	/**
	 * Right-click freezes the tip in place. Tip dismisses when the pointer leaves the tip panel
	 * plus {@link #TIP_PIN_DISMISS_PAD_PX}.
	 */
	private boolean tipPinned;
	private boolean tipPinBoundsReady;
	private int tipPinnedPanelX;
	private int tipPinnedPanelY;
	private int tipPinAnchorX;
	private int tipPinAnchorY;
	private String tipPinnedWikiPage;
	private String tipPinnedInstanceId;
	private final Rectangle tipPanelBounds = new Rectangle();
	private final Rectangle closeButtonBounds = new Rectangle();
	private final Map<String, Rectangle> tipActionBounds = new HashMap<>();
	/** Grace zone outside the frozen tip before auto-hide. */
	private static final int TIP_PIN_DISMISS_PAD_PX = 48;

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
		// Above other plugin overlays (infoboxes, XP drops, etc.) while the reveal is active.
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	/**
	 * Keeps hover hit-testing aligned with {@link java.awt.event.MouseEvent#getPoint()} used for pack/card clicks.
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

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Optional<PackRevealService.RevealPaintSnapshot> snapOpt = revealService.capturePaintFrame();
		if (snapOpt.isEmpty())
		{
			if (packRevealSoundActiveLastFrame)
			{
				ForkJoinPool.commonPool().execute(() ->
				{
					try
					{
						packRevealSoundService.hardStop();
					}
					catch (Exception ignored)
					{
						// best-effort; avoid blocking the client thread on audio line teardown
					}
				});
			}
			packRevealSoundActiveLastFrame = false;
			persistSessionPackZoomIfNeeded();
			resetHoverAnimations();
			closeButtonBounds.setBounds(0, 0, 0, 0);
			clearSlotCaches();
			return null;
		}
		PackRevealService.RevealPaintSnapshot snap = snapOpt.get();
		packRevealSoundActiveLastFrame = true;

		Rectangle canvas = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		PackRevealDrawUtil.drawDim(graphics, canvas);

		List<PackRevealService.RevealCard> cards = snap.getCards();
		int cardCount = cards.size();
		invalidateFaceSlotsIfVisibleCardsChanged(cards);
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
						packImageDrawRect(packScaled, snap.getBoosterPackId()),
						PACK_SEALED_GLOW_INSET);
					PackRevealDrawUtil.drawGlow(graphics, packGlowRect, RarityMath.Tier.GODLY.getColor(), glowAlpha);
				}
			}
			else
			{
				apexPackPointerWasInside = false;
			}
			drawPackImage(graphics, packScaled, 1.0f, snap.getBoosterPackId());
			paintRevealChrome(graphics, canvas, snap, null);
			clearCardInfoTip();
			return null;
		}

		if (phase == PackRevealService.Phase.PACK_FADING || phase == PackRevealService.Phase.AWAITING_PULLS)
		{
			// Stationary deal pile under the pack (placeholders or real pulls) as the pack fades out.
			// AWAITING_PULLS is only used when pack size was unknown (no placeholders) after fade.
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
			paintRevealChrome(graphics, canvas, snap, null);
			clearCardInfoTip();
			return null;
		}

		if (phase == PackRevealService.Phase.CARD_DEAL)
		{
			drawDealPhase(graphics, canvas, cards, layout, cardCount, snap.getPhaseElapsedMs());
			// Final slot size (not mid-flight rects) - same bounds used once cards land.
			prewarmNextRevealFace(cards, PackRevealLayout.layoutCardSlots(canvas, cardCount, layout));
			paintRevealChrome(graphics, canvas, snap, null);
			clearCardInfoTip();
			return null;
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
				// Fade glow out to edge-on, then back in as the face comes around.
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
		}
		for (int i : drawOrder)
		{
			PackRevealService.RevealCard card = cards.get(i);
			RevealCardVisual visual = revealCardVisual(i, bounds.get(i), snap);
			Rectangle r = visual.rect;
			boolean faceUp = visual.faceUp;
			double lift = visual.lift;
			if (faceUp && visual.flipProgress >= 1f)
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
	 * Face draw parameters for one pulled card, including the deterministic wear/foil FX seeded from the
	 * pull provenance so the reveal matches the website's inspect view for the same instance.
	 * The server does not send {@code beta} on a pull, so an absent condition simply means no wear.
	 * FoilFx / WearFx lists are reused per slot so sparkle/wear seeds are not rebuilt every frame.
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
			&& java.util.Objects.equals(slot.artPath, artPath))
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

	/** Banded detail URL, or foil art path when this pull should render foil bleed. */
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
	 * Queues a few face rasters onto a background pool while cards are dealing / waiting to flip.
	 * Never rasterizes foil/wear on the client thread.
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
			// Slot reused across deal pages - don't treat a prior card's bake as done.
			if (facePrewarmDone[i] && slot != null && java.util.Objects.equals(slot.artPath, artPath)
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
				// Art still loading - retry prewarm once bytes are in memory; never bake "Loading artwork...".
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

	private void scheduleFacePrewarm(int index, int width, int height, CardFaceDrawRequest req)
	{
		if (req == null || index < 0 || index >= facePrewarmScheduled.length || facePrewarmScheduled[index])
		{
			return;
		}
		facePrewarmScheduled[index] = true;
		ForkJoinPool.commonPool().execute(() -> SharedCardRenderer.prewarmFace(width, height, req));
	}

	/** Storage identity for wear seeds - must match website (cardName / npc:{id}), not display title. */
	private static String seedNameFor(PackRevealService.RevealCard card)
	{
		if (card.getPull() != null && card.getPull().getCardName() != null
			&& !card.getPull().getCardName().trim().isEmpty())
		{
			return card.getPull().getCardName().trim();
		}
		return "";
	}

	/**
	 * When the visible deal page changes (or placeholders become real pulls), slot indices reuse the same
	 * cache entries. Clear per-slot FX / prewarm flags so the new faces can bake.
	 */
	private void invalidateFaceSlotsIfVisibleCardsChanged(List<PackRevealService.RevealCard> cards)
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
	 * Draws a card with a horizontal Y-flip squash matching website inspect
	 * ({@code rotateY} over 550ms). Texture swaps after 90°.
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
					SharedCardRenderer.drawCardBack(g2, r, card.getPull().isFoil(), card.getRarityColor(),
						cardBackImage());
				}
				else
				{
					CardFaceDrawRequest req = cachedFaceRequest(index, card, linked, r.width, r.height);
					if (!SharedCardRenderer.drawCardFaceIfCached(g2, r, req))
					{
						SharedCardRenderer.drawCardBack(g2, r, card.getPull().isFoil(), card.getRarityColor(),
							cardBackImage());
						scheduleFacePrewarm(index, r.width, r.height, req);
					}
				}
			}
			else
			{
				SharedCardRenderer.drawCardBack(g2, r, card.getPull().isFoil(), card.getRarityColor(),
					cardBackImage());
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	private static final class RevealCardVisual
	{
		private final Rectangle rect;
		private final boolean faceUp;
		private final double lift;
		private final float flipProgress;

		private RevealCardVisual(Rectangle rect, boolean faceUp, double lift, float flipProgress)
		{
			this.rect = rect;
			this.faceUp = faceUp;
			this.lift = lift;
			this.flipProgress = flipProgress;
		}
	}

	public Rectangle currentPackBounds()
	{
		synchronized (revealService)
		{
			if (!revealService.isActive() || revealService.getPhase() != PackRevealService.Phase.PACK_READY)
			{
				return null;
			}
			Rectangle canvas = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
			PackRevealLayout.ViewportLayout layout = computeViewportLayout(canvas, revealService.getCards().size());
			Rectangle packBase = layout.packRect(canvas);
			return packDrawRect(packBase);
		}
	}

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
			Rectangle canvas = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
			int n = revealService.getCards().size();
			List<Rectangle> bases = PackRevealLayout.layoutCardSlots(canvas, n, computeViewportLayout(canvas, n));
			return withCardHoverVisualScale(bases);
		}
	}

	/**
	 * Face-up reveal card under the canvas pointer, or {@code null} when none / still face-down.
	 */
	public PackRevealService.RevealCard faceUpCardAt(Point canvasPoint)
	{
		if (canvasPoint == null)
		{
			return null;
		}
		synchronized (revealService)
		{
			List<Rectangle> bounds = currentCardBounds();
			List<PackRevealService.RevealCard> cards = revealService.getCards();
			if (bounds.isEmpty() || cards.isEmpty())
			{
				return null;
			}
			for (int i = 0; i < bounds.size() && i < cards.size(); i++)
			{
				Rectangle r = bounds.get(i);
				if (r != null && r.contains(canvasPoint) && revealService.isCardRevealed(i))
				{
					return cards.get(i);
				}
			}
			return null;
		}
	}

	private void paintScrollHintOnTop(Graphics2D g, Rectangle canvas, PackRevealService.RevealPaintSnapshot snap)
	{
		if (!snap.isShowScrollWheelOverlayHint())
		{
			return;
		}
		PackRevealDrawUtil.drawScrollWheelHint(g, canvas);
	}

	private void paintRevealChrome(Graphics2D graphics, Rectangle canvas, PackRevealService.RevealPaintSnapshot snap,
		List<PackRevealService.RevealCard> cards)
	{
		paintScrollHintOnTop(graphics, canvas, snap);
		paintCloseButton(graphics, canvas);
		if (cards != null)
		{
			paintCardInfoTip(graphics, canvas, cards);
		}
	}

	private void paintCloseButton(Graphics2D g, Rectangle canvas)
	{
		PackRevealDrawUtil.layoutCloseButton(canvas, closeButtonBounds);
		boolean hover = false;
		if (revealPointer(pointerScratch))
		{
			Point p = new Point(pointerScratch[0], pointerScratch[1]);
			hover = closeButtonBounds.contains(p) && !cardInfoTipCoversPoint(p);
		}
		PackRevealDrawUtil.drawCloseButton(g, closeButtonBounds, hover);
	}

	/**
	 * @return true when the click should close the reveal (same path as Esc / Safe-mode dismiss)
	 */
	public boolean handleCloseButtonClick(Point canvasPoint)
	{
		if (canvasPoint == null || closeButtonBounds.width <= 0)
		{
			return false;
		}
		if (!closeButtonBounds.contains(canvasPoint))
		{
			return false;
		}
		return !cardInfoTipCoversPoint(canvasPoint);
	}

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

	private boolean mouseInRect(Rectangle r)
	{
		if (r == null)
		{
			return false;
		}
		return revealPointer(pointerScratch) && r.contains(pointerScratch[0], pointerScratch[1]);
	}

	private void persistSessionPackZoomIfNeeded()
	{
		if (!Double.isNaN(sessionPackZoomMultiplier))
		{
			persistPackRevealScale(sessionPackZoomMultiplier);
		}
	}

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
	 * Right-click on a face-up card: freeze the card tip and append context-menu actions
	 * (Inspect / Open wiki page) when available.
	 *
	 * @return true when the tip was pinned
	 */
	public boolean pinCardInfoTipAt(Point canvasPoint)
	{
		PackRevealService.RevealCard card = faceUpCardAt(canvasPoint);
		String wikiPage = CardInfoTipModel.wikiPageFor(card);
		String instanceId = CardInfoTipModel.instanceIdFor(card);
		if (card == null || canvasPoint == null || (wikiPage == null && instanceId == null))
		{
			return false;
		}
		int index = -1;
		synchronized (revealService)
		{
			List<PackRevealService.RevealCard> cards = revealService.getCards();
			for (int i = 0; i < cards.size(); i++)
			{
				if (cards.get(i) == card)
				{
					index = i;
					break;
				}
			}
		}
		if (index < 0)
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
		// Skip hover delay / fade so the menu appears immediately.
		tipHoverStartedAtMs = System.currentTimeMillis() - CardInfoTipModel.DELAY_MS - CardInfoTipModel.FADE_IN_MS;
		tipActionBounds.clear();
		tipPanelBounds.setBounds(0, 0, 0, 0);
		return true;
	}

	public boolean isCardInfoTipPinned()
	{
		return tipPinned;
	}

	/**
	 * Left-click while the tip is pinned. Opens inspect/wiki when an action row is hit; otherwise dismisses.
	 *
	 * @return true when the click was fully consumed (do not advance the reveal)
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
		// Click on the tip body dismisses without advancing; click elsewhere dismisses and continues.
		return onTip;
	}

	/**
	 * Arms the website-style info tip for settled face-up cards only (180 ms delay).
	 * Hover tip sits in the top-right of the canvas; the right-click context menu still pins near the cursor.
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

	private boolean cursorNearPinnedTip(int mx, int my)
	{
		int pad = TIP_PIN_DISMISS_PAD_PX;
		return mx >= tipPanelBounds.x - pad
			&& my >= tipPanelBounds.y - pad
			&& mx < tipPanelBounds.x + tipPanelBounds.width + pad
			&& my < tipPanelBounds.y + tipPanelBounds.height + pad;
	}

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
			// Ease-out: 1 - (1-t)^2
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

	/** One-shot premium hum per reveal when a qualifying card is still face-down after the pack opens. */
	private void tryPlayMythicHum(PackRevealService.Phase phase, PackRevealService.RevealPaintSnapshot snap)
	{
		boolean humWanted = phase != PackRevealService.Phase.PACK_READY && snap.hasUnrevealedMythic();
		packRevealSoundService.tryPlayMythicHum(humWanted);
	}

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

	/**
	 * Lerp factor for elapsed wall time since the last hover update. Matches {@link #HOVER_LERP} per frame at
	 * {@link #HOVER_LERP_REFERENCE_HZ} so feel is unchanged at 60 FPS but hover no longer slows when FPS drops.
	 */
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

	private static double stepToward(double current, double target, double factor)
	{
		return current + (target - current) * factor;
	}

	private Rectangle packDrawRect(Rectangle packBase)
	{
		double scale = 1.0d + (PACK_IMAGE_HOVER_MAX_SCALE - 1.0d) * packHoverLift;
		return PackRevealDrawUtil.scaleRectCentered(packBase, scale);
	}

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

	/**
	 * Preferred zoom mul: session wheel choice, else the persisted scale from state
	 * (clamped to discrete levels). Applied size is still capped by canvas fit.
	 */
	private double preferredZoomMultiplier()
	{
		if (!Double.isNaN(sessionPackZoomMultiplier))
		{
			return sessionPackZoomMultiplier;
		}
		return readPersistedPackRevealScale();
	}

	private double readPersistedPackRevealScale()
	{
		return PackRevealZoomUtil.clamp(tcgStateService.getState().getPackRevealOverlayScale());
	}

	private void persistPackRevealScale(double multiplier)
	{
		tcgStateService.setPackRevealOverlayScale(multiplier);
	}

	/**
	 * Wheel steps size preference through {@link PackRevealZoomUtil#LEVELS}. Layout still uses the
	 * largest preferred level that fits the canvas.
	 */
	public void nudgeSessionPackZoom(int wheelRotation)
	{
		if (wheelRotation == 0)
		{
			return;
		}
		double base = preferredZoomMultiplier();
		sessionPackZoomMultiplier = PackRevealZoomUtil.nudge(base, wheelRotation);
		persistPackRevealScale(sessionPackZoomMultiplier);
		invalidateFaceSizes();
	}

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


	private PackRevealLayout.ViewportLayout computeViewportLayout(Rectangle canvas, int cardCount)
	{
		return computeViewportLayout(canvas, cardCount, revealService.getPhase());
	}

	private PackRevealLayout.ViewportLayout computeViewportLayout(Rectangle canvas, int cardCount, PackRevealService.Phase phase)
	{
		return PackRevealLayout.computeViewportLayout(canvas, cardCount, phase, preferredZoomMultiplier(), this::noteAppliedZoom);
	}

	private void noteAppliedZoom(double zoomMul)
	{
		if (Double.compare(lastAppliedZoomMul, zoomMul) != 0)
		{
			lastAppliedZoomMul = zoomMul;
			invalidateFaceSizes();
		}
	}

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
			PackRevealDrawUtil.drawGlow(graphics, r, card.getRarityColor(), 0f);
			SharedCardRenderer.drawCardBack(graphics, r, card.getPull().isFoil(), card.getRarityColor(),
				cardBackImage());
		}
	}

	/**
	 * Pixel rect where pack PNG (or card-back fallback) is drawn inside {@code bounds}; matches drawImageFit.
	 */
	private Rectangle packImageDrawRect(Rectangle bounds, String boosterPackId)
	{
		return PackRevealDrawUtil.fittedImageRect(bounds, packArtForPackId(boosterPackId));
	}

	private void drawPackImage(Graphics2D g, Rectangle bounds, float alpha, String boosterPackId)
	{
		BufferedImage packArt = packArtForPackId(boosterPackId);
		if (packArt != null)
		{
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
			PackRevealDrawUtil.drawImageFit(g, packArt, bounds);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
			return;
		}

		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
		SharedCardRenderer.drawCardBack(g, bounds, false, Color.WHITE, cardBackImage());
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
	}

	private BufferedImage cardBackImage()
	{
		return imageCacheService.getCached(SharedCardRenderer.CARD_BACK_PATH);
	}

	private BufferedImage packArtForPackId(String boosterPackId)
	{
		if (boosterPackId == null || boosterPackId.isBlank())
		{
			return null;
		}
		BoosterPackDefinition pack = packCatalogService.getCache().get(boosterPackId).orElse(null);
		String imagePath = PackImageUrls.revealSleevePath(pack);
		if (imagePath == null)
		{
			return null;
		}
		return imageCacheService.getCached(imagePath);
	}

	static double clampPackRevealZoomMultiplier(double value)
	{
		return PackRevealZoomUtil.clamp(value);
	}

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

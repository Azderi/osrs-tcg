package com.runelitetcg.overlay;

import com.runelitetcg.service.PackRevealSoundService;
import com.runelitetcg.service.PackRevealService;
import com.runelitetcg.service.RarityMath;
import com.runelitetcg.service.TcgStateService;
import com.runelitetcg.service.WikiImageCacheService;
import com.runelitetcg.ui.SharedCardRenderer;
import com.runelitetcg.util.PackRevealZoomUtil;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

@Singleton
public class PackRevealOverlay extends Overlay
{
	private static final double CARD_SIZE_SCALE = 0.805d * 1.25d;
	private static final int BASE_CARD_W = (int) Math.round(SharedCardRenderer.DEFAULT_CARD_WIDTH * CARD_SIZE_SCALE);
	private static final int BASE_CARD_H = (int) Math.round(SharedCardRenderer.DEFAULT_CARD_HEIGHT * CARD_SIZE_SCALE);
	/** Sealed pack sprite bounds (before viewport scale). Larger than cards so the pack reads clearly in the opening beat. */
	private static final int BASE_PACK_W = 396;
	private static final int BASE_PACK_H = 545;
	/** Hovered revealed card scales up slightly (centered). */
	private static final double HOVER_CARD_SCALE = 1.072d;
	/** Sealed pack grows slightly when hovered (PACK_READY). */
	private static final double PACK_IMAGE_HOVER_MAX_SCALE = 1.085d;
	/** Per-step lerp at {@value #HOVER_LERP_REFERENCE_HZ} Hz; converted to wall-clock in {@link #advanceHoverLerpFactor()}. */
	private static final double HOVER_LERP = 0.22d;
	private static final double HOVER_LERP_REFERENCE_HZ = 60.0d;
	private static final double HOVER_LERP_MAX_DT_SEC = 0.05d;
	private static final int BASE_CARD_GAP = 24;
	/** Inset from canvas edges when fitting pack + card grid. */
	private static final int VIEWPORT_MARGIN = 40;
	private static final double MIN_OVERLAY_SCALE = 0.28d;
	/** Clamped when applied so the layout never collapses or overflows wildly. */
	static final double PACK_REVEAL_ZOOM_MIN = PackRevealZoomUtil.MIN;
	static final double PACK_REVEAL_ZOOM_MAX = PackRevealZoomUtil.MAX;
	/** Per notch when scrolling during pack reveal (scroll up = zoom in). */
	private static final double WHEEL_ZOOM_STEP_RATIO = 1.08d;
	/** Pixel offset per card in the pre-deal stack (waiting cards). */
	private static final int DEAL_STACK_STEP = 5;
	/** Max rarity glow alpha when a card is fully hovered (no glow when not hovered). */
	private static final float HOVER_RARITY_GLOW_ALPHA = 0.17f;
	/** Tucks the apex sealed-pack glow slightly inside the letterboxed art rect (px per edge). */
	private static final int PACK_SEALED_GLOW_INSET = 2;

	/** Cached pack sleeve art by Packs.json {@code id} (empty string = standard / unknown). */
	private static final ConcurrentHashMap<String, BufferedImage> PACK_ART_BY_ID = new ConcurrentHashMap<>();

	private final Client client;
	private final PackRevealService revealService;
	private final WikiImageCacheService imageCacheService;
	private final PackRevealSoundService packRevealSoundService;
	private final TcgStateService tcgStateService;

	/** {@link Double#NaN} until the wheel adjusts zoom this session; then a clamped multiplier on top of the fitted layout. */
	private volatile double sessionPackZoomMultiplier = Double.NaN;

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
	/** Used to call {@link PackRevealSoundService#hardStop()} only on active→inactive transition (not every frame). */
	private boolean packRevealSoundActiveLastFrame;
	/** Wall-clock source for time-based hover lerp (stable speed when overlay FPS drops at high zoom). */
	private long lastHoverDynamicsNanos;

	@Inject
	public PackRevealOverlay(Client client, PackRevealService revealService, WikiImageCacheService imageCacheService,
		PackRevealSoundService packRevealSoundService, TcgStateService tcgStateService)
	{
		this.client = client;
		this.revealService = revealService;
		this.imageCacheService = imageCacheService;
		this.packRevealSoundService = packRevealSoundService;
		this.tcgStateService = tcgStateService;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGH);
	}

	/**
	 * Keeps hover hit-testing aligned with {@link java.awt.event.MouseEvent#getPoint()} used for pack/card clicks.
	 */
	public void setRevealHoverCanvasPoint(Point canvasPoint)
	{
		if (canvasPoint == null)
		{
			revealHoverFromListener = false;
			return;
		}
		revealHoverCanvasX = canvasPoint.x;
		revealHoverCanvasY = canvasPoint.y;
		revealHoverFromListener = true;
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
			return null;
		}
		PackRevealService.RevealPaintSnapshot snap = snapOpt.get();
		packRevealSoundActiveLastFrame = true;

		Rectangle canvas = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		drawDim(graphics, canvas);

		List<PackRevealService.RevealCard> cards = snap.getCards();
		int cardCount = cards.size();
		ViewportLayout layout = computeViewportLayout(canvas, cardCount);
		PackRevealService.Phase phase = snap.getPhase();
		if (phase != PackRevealService.Phase.PACK_READY)
		{
			apexPackPointerWasInside = false;
		}
		updateHoverDynamics(canvas, layout, cardCount, phase, snap.getPhaseElapsedMs());
		tickMythicHum(phase, snap);
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
				float glowAlpha = (float) (HOVER_RARITY_GLOW_ALPHA * Math.max(0.22d, packHoverLift));
				Rectangle packGlowRect = uniformInset(
					packImageDrawRect(packScaled, snap.getBoosterPackId()),
					PACK_SEALED_GLOW_INSET);
				drawGlow(graphics, packGlowRect, RarityMath.Tier.GODLY.getColor(), glowAlpha);
			}
			else
			{
				apexPackPointerWasInside = false;
			}
			drawPackImage(graphics, packScaled, 1.0f, snap.getBoosterPackId());
			paintScrollHintOnTop(graphics, canvas, snap);
			return null;
		}

		if (phase == PackRevealService.Phase.PACK_FADING)
		{
			double progress = snap.getPackFadeProgress();
			Rectangle packBounds = layout.packRect(canvas);
			float packAlpha = (float) Math.max(0.0d, 1.0d - progress);
			if (packAlpha > 0.01f)
			{
				drawPackImage(graphics, packBounds, packAlpha, snap.getBoosterPackId());
			}
			paintScrollHintOnTop(graphics, canvas, snap);
			return null;
		}

		if (phase == PackRevealService.Phase.CARD_DEAL)
		{
			drawDealPhase(graphics, canvas, cards, layout, cardCount, snap.getPhaseElapsedMs());
			paintScrollHintOnTop(graphics, canvas, snap);
			return null;
		}

		List<Rectangle> bounds = layoutCardSlots(canvas, cardCount, layout);
		List<Integer> drawOrder = new ArrayList<>(cards.size());
		for (int i = 0; i < cards.size(); i++)
		{
			drawOrder.add(i);
		}
		drawOrder.sort(Comparator.comparingDouble(i -> cardHoverLift[i]));
		for (int i : drawOrder)
		{
			PackRevealService.RevealCard card = cards.get(i);
			Rectangle r = bounds.get(i);
			boolean faceUp = snap.isCardRevealed(i);
			double lift = faceUp ? 0.0d : ((i >= 0 && i < cardHoverLift.length) ? cardHoverLift[i] : 0.0d);
			if (!faceUp && lift > 0.0d)
			{
				double scale = 1.0d + (HOVER_CARD_SCALE - 1.0d) * lift;
				r = scaleRectCentered(r, scale);
			}
			float glowAlpha = faceUp ? HOVER_RARITY_GLOW_ALPHA : (float) (HOVER_RARITY_GLOW_ALPHA * lift);
			drawGlow(graphics, r, card.getRarityColor(), glowAlpha);
			if (faceUp)
			{
				BufferedImage linked = imageCacheService.getCached(card.getDefinition() == null ? null : card.getDefinition().getImageUrl());
				SharedCardRenderer.drawCardFace(
					graphics,
					r,
					card.getDefinition(),
					card.getPull().isFoil(),
					card.getRarityColor(),
					linked,
					card.getBasePullDenominator(),
					card.getPull().isFoil());
				if (card.isNew())
				{
					drawNewBadge(graphics, r);
				}
			}
			else
			{
				SharedCardRenderer.drawCardBack(graphics, r, card.getPull().isFoil(), card.getRarityColor());
			}
		}

		paintScrollHintOnTop(graphics, canvas, snap);
		return null;
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
			ViewportLayout layout = computeViewportLayout(canvas, revealService.getCards().size());
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
				|| phase == PackRevealService.Phase.PACK_FADING || phase == PackRevealService.Phase.CARD_DEAL)
			{
				return List.of();
			}
			Rectangle canvas = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
			int n = revealService.getCards().size();
			List<Rectangle> bases = layoutCardSlots(canvas, n, computeViewportLayout(canvas, n));
			return withCardHoverVisualScale(bases);
		}
	}

	private void drawDim(Graphics2D g, Rectangle canvas)
	{
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		g.setColor(Color.BLACK);
		g.fillRect(canvas.x, canvas.y, canvas.width, canvas.height);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
	}

	private void paintScrollHintOnTop(Graphics2D g, Rectangle canvas, PackRevealService.RevealPaintSnapshot snap)
	{
		if (!snap.isShowScrollWheelOverlayHint())
		{
			return;
		}
		drawScrollWheelHint(g, canvas);
	}

	private void drawScrollWheelHint(Graphics2D g, Rectangle canvas)
	{
		String text = "Use scrollwheel to adjust the overlay";
		Font font = FontManager.getRunescapeBoldFont();
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics(font);
		int tw = fm.stringWidth(text);
		int x = canvas.x + (canvas.width - tw) / 2;
		int y = canvas.y + Math.max(32, VIEWPORT_MARGIN) + fm.getAscent();
		g.setColor(new Color(0, 0, 0, 220));
		g.drawString(text, x + 2, y + 2);
		g.setColor(new Color(0xFF, 0xF5, 0xDC));
		g.drawString(text, x, y);
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
		revealHoverFromListener = false;
		apexPackPointerWasInside = false;
		lastHoverDynamicsNanos = 0L;
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

	/** Premium hum loops while any face-down card that qualifies for hum/reveal remains, after the sealed pack is opened (not on {@link PackRevealService.Phase#PACK_READY}). */
	private void tickMythicHum(PackRevealService.Phase phase, PackRevealService.RevealPaintSnapshot snap)
	{
		boolean humWanted = phase != PackRevealService.Phase.PACK_READY && snap.hasUnrevealedMythic();
		packRevealSoundService.tickMythicHum(true, humWanted);
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

	private void updateHoverDynamics(Rectangle canvas, ViewportLayout layout, int cardCount,
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

		if (phase == PackRevealService.Phase.PACK_FADING)
		{
			packHoverLift = stepToward(packHoverLift, 0.0d, lerp);
			decayAllCardHovers(lerp);
			return;
		}

		if (phase == PackRevealService.Phase.CARD_DEAL)
		{
			packHoverLift = stepToward(packHoverLift, 0.0d, lerp);
			ensureCardHoverLength(cardCount);
			List<Rectangle> bases = layoutDealPhaseCardRects(canvas, layout, cardCount, phaseElapsedMs);
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
			List<Rectangle> bases = layoutCardSlots(canvas, cardCount, layout);
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
		return scaleRectCentered(packBase, scale);
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
			out.add(scaleRectCentered(bases.get(i), scale));
		}
		return out;
	}

	private double zoomMultiplierForLayout()
	{
		if (Double.isNaN(sessionPackZoomMultiplier))
		{
			return readPersistedPackRevealScale();
		}
		return sessionPackZoomMultiplier;
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
	 * Wheel-driven zoom for the current reveal session; persisted in {@link com.runelitetcg.model.TcgState}.
	 */
	public void nudgeSessionPackZoom(int wheelRotation)
	{
		if (wheelRotation == 0)
		{
			return;
		}
		double base = Double.isNaN(sessionPackZoomMultiplier)
			? readPersistedPackRevealScale()
			: sessionPackZoomMultiplier;
		sessionPackZoomMultiplier = PackRevealZoomUtil.clamp(
			base * Math.pow(WHEEL_ZOOM_STEP_RATIO, -wheelRotation));
		persistPackRevealScale(sessionPackZoomMultiplier);
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

	private static Rectangle scaleRectCentered(Rectangle r, double scale)
	{
		int nw = Math.max(1, (int) Math.round(r.width * scale));
		int nh = Math.max(1, (int) Math.round(r.height * scale));
		int nx = r.x + (r.width - nw) / 2;
		int ny = r.y + (r.height - nh) / 2;
		return new Rectangle(nx, ny, nw, nh);
	}

	private static Rectangle uniformInset(Rectangle r, int inset)
	{
		if (inset <= 0)
		{
			return new Rectangle(r);
		}
		int nw = Math.max(1, r.width - 2 * inset);
		int nh = Math.max(1, r.height - 2 * inset);
		return new Rectangle(r.x + inset, r.y + inset, nw, nh);
	}

	private void drawGlow(Graphics2D g, Rectangle r, Color color, float alpha)
	{
		drawGlow(g, r, color, alpha, 26f, 18, 20);
	}

	/**
	 * @param maxExpand outer halo reach in pixels (smaller = tighter around {@code r})
	 */
	private void drawGlow(Graphics2D g, Rectangle r, Color color, float alpha, float maxExpand, int layers, int baseArc)
	{
		Color glow = color == null ? Color.WHITE : color;
		float clampedAlpha = Math.max(0f, Math.min(1f, alpha));
		if (clampedAlpha <= 0.01f)
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			// Stable soft edge glow from card bounds.
			for (int i = layers; i >= 1; i--)
			{
				float t = (float) i / (float) layers; // 1 near card, 0 far.
				int expand = Math.max(1, Math.round((1.0f - t) * maxExpand));
				float falloff = t * t; // smooth quadratic falloff
				float layerAlpha = clampedAlpha * falloff * 0.34f;
				g2.setColor(withAlpha(glow, layerAlpha));
				int arc = baseArc + expand;
				g2.fillRoundRect(
					r.x - expand,
					r.y - expand,
					r.width + (expand * 2),
					r.height + (expand * 2),
					arc,
					arc
				);
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	private static int naturalGridWidth(int count)
	{
		if (count <= 0)
		{
			return 0;
		}
		int topCount = Math.min(2, count);
		int bottomCount = Math.max(0, count - topCount);
		int topWidth = (topCount * BASE_CARD_W) + (Math.max(0, topCount - 1) * BASE_CARD_GAP);
		int bottomWidth = (bottomCount * BASE_CARD_W) + (Math.max(0, bottomCount - 1) * BASE_CARD_GAP);
		return Math.max(topWidth, bottomWidth);
	}

	private static int naturalGridHeight(int count)
	{
		if (count <= 0)
		{
			return 0;
		}
		int topCount = Math.min(2, count);
		int bottomCount = Math.max(0, count - topCount);
		return (bottomCount > 0) ? (BASE_CARD_H * 2) + BASE_CARD_GAP : BASE_CARD_H;
	}

	/**
	 * Scale pack and cards together so the larger of pack vs card grid fits inside the canvas minus margin.
	 */
	private ViewportLayout computeViewportLayout(Rectangle canvas, int cardCount)
	{
		int availW = Math.max(80, canvas.width - 2 * VIEWPORT_MARGIN);
		int availH = Math.max(80, canvas.height - 2 * VIEWPORT_MARGIN);
		int gridW = naturalGridWidth(cardCount);
		int gridH = naturalGridHeight(cardCount);
		double needW = Math.max(BASE_PACK_W, gridW);
		double needH = Math.max(BASE_PACK_H, gridH);
		double maxS = Math.min(availW / needW, availH / needH);
		double fitS = Math.min(1.0d, maxS);
		fitS = Math.max(MIN_OVERLAY_SCALE, fitS);
		double zoomMul = zoomMultiplierForLayout();
		double s = Math.max(MIN_OVERLAY_SCALE, Math.min(maxS, fitS * zoomMul));
		int packW = Math.max(1, (int) Math.round(BASE_PACK_W * s));
		int packH = Math.max(1, (int) Math.round(BASE_PACK_H * s));
		int cardW = Math.max(1, (int) Math.round(BASE_CARD_W * s));
		int cardH = Math.max(1, (int) Math.round(BASE_CARD_H * s));
		int gap = Math.max(4, (int) Math.round(BASE_CARD_GAP * s));
		return new ViewportLayout(packW, packH, cardW, cardH, gap);
	}

	private List<Rectangle> layoutCardSlots(Rectangle canvas, int count, ViewportLayout layout)
	{
		List<Rectangle> out = new ArrayList<>();
		if (count <= 0)
		{
			return out;
		}
		int cw = layout.cardW;
		int ch = layout.cardH;
		int g = layout.gap;
		int topCount = Math.min(2, count);
		int bottomCount = Math.max(0, count - topCount);
		int topWidth = (topCount * cw) + (Math.max(0, topCount - 1) * g);
		int bottomWidth = (bottomCount * cw) + (Math.max(0, bottomCount - 1) * g);
		int maxWidth = Math.max(topWidth, bottomWidth);
		int totalHeight = (bottomCount > 0) ? (ch * 2) + g : ch;

		int originX = canvas.x + (canvas.width - maxWidth) / 2;
		int originY = canvas.y + (canvas.height - totalHeight) / 2;

		int topStartX = originX + (maxWidth - topWidth) / 2;
		for (int i = 0; i < topCount; i++)
		{
			out.add(new Rectangle(topStartX + i * (cw + g), originY, cw, ch));
		}

		int bottomStartX = originX + (maxWidth - bottomWidth) / 2;
		for (int i = 0; i < bottomCount; i++)
		{
			out.add(new Rectangle(bottomStartX + i * (cw + g), originY + ch + g, cw, ch));
		}
		return out;
	}

	private static final class ViewportLayout
	{
		final int packW;
		final int packH;
		final int cardW;
		final int cardH;
		final int gap;

		ViewportLayout(int packW, int packH, int cardW, int cardH, int gap)
		{
			this.packW = packW;
			this.packH = packH;
			this.cardW = cardW;
			this.cardH = cardH;
			this.gap = gap;
		}

		Rectangle packRect(Rectangle canvas)
		{
			int x = canvas.x + (canvas.width - packW) / 2;
			int y = canvas.y + (canvas.height - packH) / 2;
			return new Rectangle(x, y, packW, packH);
		}
	}

	private static Rectangle lerp(Rectangle from, Rectangle to, double t)
	{
		int x = (int) Math.round(from.x + ((to.x - from.x) * t));
		int y = (int) Math.round(from.y + ((to.y - from.y) * t));
		int w = (int) Math.round(from.width + ((to.width - from.width) * t));
		int h = (int) Math.round(from.height + ((to.height - from.height) * t));
		return new Rectangle(x, y, w, h);
	}

	private void drawDealPhase(Graphics2D graphics, Rectangle canvas, List<PackRevealService.RevealCard> cards,
		ViewportLayout layout, int cardCount, long phaseElapsedMs)
	{
		long stagger = PackRevealService.PACK_DEAL_STAGGER_MS;
		long flight = PackRevealService.PACK_DEAL_FLIGHT_MS;
		List<Rectangle> rects = layoutDealPhaseCardRects(canvas, layout, cardCount, phaseElapsedMs);

		List<Integer> order = new ArrayList<>(cardCount);
		for (int i = 0; i < cardCount; i++)
		{
			order.add(i);
		}
		order.sort(Comparator
			.comparingInt((Integer i) -> dealDrawLayer(phaseElapsedMs, i, stagger, flight))
			.thenComparingInt(i -> i));

		for (int i : order)
		{
			PackRevealService.RevealCard card = cards.get(i);
			Rectangle r = rects.get(i);
			drawGlow(graphics, r, card.getRarityColor(), 0f);
			SharedCardRenderer.drawCardBack(graphics, r, card.getPull().isFoil(), card.getRarityColor());
		}
	}

	private List<Rectangle> layoutDealPhaseCardRects(Rectangle canvas, ViewportLayout layout, int cardCount, long elapsed)
	{
		List<Rectangle> out = new ArrayList<>(cardCount);
		if (cardCount <= 0)
		{
			return out;
		}
		long stagger = PackRevealService.PACK_DEAL_STAGGER_MS;
		long flight = PackRevealService.PACK_DEAL_FLIGHT_MS;
		List<Rectangle> slots = layoutCardSlots(canvas, cardCount, layout);
		int cw = layout.cardW;
		int ch = layout.cardH;
		Rectangle grid = unionBounds(slots);
		int cx = grid.x + grid.width / 2;
		int cy = grid.y + grid.height / 2;
		Rectangle pileCenterRect = new Rectangle(cx - cw / 2, cy - ch / 2, cw, ch);
		for (int i = 0; i < cardCount; i++)
		{
			out.add(dealPhaseCardRect(i, elapsed, stagger, flight, slots, pileCenterRect, cw, ch, cardCount));
		}
		return out;
	}

	/** 0 = landed (bottom), 1 = waiting in pile, 2 = in flight (top). */
	private static int dealDrawLayer(long elapsed, int i, long stagger, long flight)
	{
		long t0 = (long) i * stagger;
		long t1 = t0 + flight;
		if (elapsed >= t1)
		{
			return 0;
		}
		if (elapsed < t0)
		{
			return 1;
		}
		return 2;
	}

	private static Rectangle dealPhaseCardRect(int i, long elapsed, long stagger, long flight,
		List<Rectangle> slots, Rectangle pileCenterRect, int cw, int ch, int n)
	{
		long t0 = (long) i * stagger;
		long t1 = t0 + flight;
		Rectangle dest = slots.get(i);
		if (elapsed >= t1)
		{
			return dest;
		}
		if (elapsed < t0)
		{
			List<Integer> waiting = new ArrayList<>();
			for (int j = 0; j < n; j++)
			{
				if (elapsed < (long) j * stagger)
				{
					waiting.add(j);
				}
			}
			Collections.sort(waiting);
			int rank = waiting.indexOf(i);
			if (rank < 0)
			{
				rank = 0;
			}
			int off = rank * DEAL_STACK_STEP;
			return new Rectangle(pileCenterRect.x + off, pileCenterRect.y + off, cw, ch);
		}
		double u = clamp01((elapsed - t0) / (double) flight);
		double t = smoothStep(u);
		return lerp(pileCenterRect, dest, t);
	}

	private static Rectangle unionBounds(List<Rectangle> rects)
	{
		if (rects == null || rects.isEmpty())
		{
			return new Rectangle();
		}
		Rectangle u = new Rectangle(rects.get(0));
		for (int i = 1; i < rects.size(); i++)
		{
			u.add(rects.get(i));
		}
		return u;
	}

	private static double clamp01(double v)
	{
		if (v <= 0.0d)
		{
			return 0.0d;
		}
		if (v >= 1.0d)
		{
			return 1.0d;
		}
		return v;
	}

	private static double smoothStep(double t)
	{
		t = clamp01(t);
		return t * t * (3.0d - 2.0d * t);
	}

	/**
	 * Pixel rect where pack PNG (or card-back fallback) is drawn inside {@code bounds}; matches {@link #drawImageFit}.
	 */
	private Rectangle packImageDrawRect(Rectangle bounds, String boosterPackId)
	{
		return fittedImageRect(bounds, packArtForPackId(boosterPackId));
	}

	private void drawPackImage(Graphics2D g, Rectangle bounds, float alpha, String boosterPackId)
	{
		BufferedImage packArt = packArtForPackId(boosterPackId);
		if (packArt != null)
		{
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
			drawImageFit(g, packArt, bounds);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
			return;
		}

		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
		SharedCardRenderer.drawCardBack(g, bounds, false, Color.WHITE);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
	}

	private static BufferedImage packArtForPackId(String boosterPackId)
	{
		String key = boosterPackId == null ? "" : boosterPackId;
		return PACK_ART_BY_ID.computeIfAbsent(key, PackRevealOverlay::loadPackArtForPackId);
	}

	/**
	 * {@code /Pack_{Title}.png} per {@code id} segment (e.g. {@code desert} → {@code Pack_Desert.png},
	 * {@code kebos_kourend} → {@code Pack_Kebos_Kourend.png}). Falls back to {@code Pack_Standard.png} / {@code Pack.png}.
	 */
	private static BufferedImage loadPackArtForPackId(String packId)
	{
		List<String> candidates = new ArrayList<>();
		if (packId == null || packId.isEmpty())
		{
			candidates.add("/Pack_Standard.png");
			candidates.add("/Pack.png");
		}
		else
		{
			candidates.add("/Pack_" + titleCasePackIdForResource(packId) + ".png");
			candidates.add("/Pack.png");
		}
		for (String path : candidates)
		{
			BufferedImage img = tryLoadImageResource(path);
			if (img != null)
			{
				return img;
			}
		}
		return null;
	}

	private static String titleCasePackIdForResource(String packId)
	{
		String[] parts = packId.split("_");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++)
		{
			String p = parts[i];
			if (p.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append('_');
			}
			sb.append(Character.toUpperCase(p.charAt(0)));
			if (p.length() > 1)
			{
				sb.append(p.substring(1).toLowerCase(Locale.ROOT));
			}
		}
		return sb.length() > 0 ? sb.toString() : "Standard";
	}

	private static BufferedImage tryLoadImageResource(String classpathPath)
	{
		try
		{
			return ImageUtil.loadImageResource(PackRevealOverlay.class, classpathPath);
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}

	private static Rectangle fittedImageRect(Rectangle bounds, BufferedImage image)
	{
		if (image == null)
		{
			return new Rectangle(bounds);
		}
		int sw = image.getWidth();
		int sh = image.getHeight();
		if (sw <= 0 || sh <= 0)
		{
			return new Rectangle(bounds);
		}
		double ratio = Math.min((double) bounds.width / (double) sw, (double) bounds.height / (double) sh);
		int w = Math.max(1, (int) Math.round(sw * ratio));
		int h = Math.max(1, (int) Math.round(sh * ratio));
		int x = bounds.x + (bounds.width - w) / 2;
		int y = bounds.y + (bounds.height - h) / 2;
		return new Rectangle(x, y, w, h);
	}

	private void drawImageFit(Graphics2D g, BufferedImage image, Rectangle bounds)
	{
		Rectangle r = fittedImageRect(bounds, image);
		g.drawImage(image, r.x, r.y, r.width, r.height, null);
	}

	private Color withAlpha(Color color, float alpha)
	{
		int a = Math.max(0, Math.min(255, (int) Math.round(alpha * 255f)));
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
	}

	private void drawNewBadge(Graphics2D g, Rectangle cardBounds)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setFont(FontManager.getRunescapeBoldFont());
			String text = "NEW!";
			int textX = cardBounds.x + (cardBounds.width / 2) - (g2.getFontMetrics().stringWidth(text) / 2);
			int textY = Math.max(14, cardBounds.y - 8);

			g2.setColor(new Color(0, 0, 0, 180));
			g2.drawString(text, textX + 1, textY + 1);
			g2.setColor(new Color(0xF2C94C));
			g2.drawString(text, textX, textY);
		}
		finally
		{
			g2.dispose();
		}
	}

	static double clampPackRevealZoomMultiplier(double value)
	{
		return PackRevealZoomUtil.clamp(value);
	}
}

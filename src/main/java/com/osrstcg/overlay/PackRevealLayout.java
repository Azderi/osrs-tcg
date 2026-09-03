package com.osrstcg.overlay;

import com.osrstcg.pack.PackRevealService;
import com.osrstcg.ui.SharedCardRenderer;
import com.osrstcg.util.PackRevealZoomUtil;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Layout math for the pack reveal overlay: base/native card and pack sizes, the zoom level that fits
 * the pack and card grid within the viewport, and the resulting slot rectangles. Pure functions, called
 * from overlay rendering on RuneLite's overlay render thread.
 */
final class PackRevealLayout
{
	static final double CARD_SIZE_SCALE = 0.805d * 1.25d;
	static final int BASE_CARD_W = (int) Math.round(SharedCardRenderer.DEFAULT_CARD_WIDTH * CARD_SIZE_SCALE);
	static final int BASE_CARD_H = (int) Math.round(SharedCardRenderer.DEFAULT_CARD_HEIGHT * CARD_SIZE_SCALE);
	static final int BASE_PACK_W = 396;
	static final int BASE_PACK_H = 545;
	static final int BASE_CARD_GAP = 24;
	static final int VIEWPORT_EDGE_PAD = 8;
	static final int SMALL_CANVAS_HEIGHT_PX = 560;
	static final double MIN_OVERLAY_SCALE = 0.28d;
	static final int CLASSIC_CANVAS_W = 765;
	static final int CLASSIC_CANVAS_H = 503;
	static final int CLASSIC_REF_CARD_COUNT = PackRevealService.MAX_VISIBLE_REVEAL_CARDS;

	static final double NATIVE_LAYOUT_SCALE = classicNativeLayoutScale();
	static final int NATIVE_CARD_W = Math.max(1, (int) Math.round(BASE_CARD_W * NATIVE_LAYOUT_SCALE));
	static final int NATIVE_CARD_H = Math.max(1, (int) Math.round(BASE_CARD_H * NATIVE_LAYOUT_SCALE));
	static final int NATIVE_PACK_W = Math.max(1, (int) Math.round(BASE_PACK_W * NATIVE_LAYOUT_SCALE));
	static final int NATIVE_PACK_H = Math.max(1, (int) Math.round(BASE_PACK_H * NATIVE_LAYOUT_SCALE));
	static final int NATIVE_CARD_GAP = Math.max(4, (int) Math.round(BASE_CARD_GAP * NATIVE_LAYOUT_SCALE));

	/** No instances; all members are static. */
	private PackRevealLayout()
	{
	}

	/**
	 * Picks the largest zoom multiplier (capped at {@code preferredZoomMul}) at which the pack (and, once
	 * cards are shown, the card grid) fits inside {@code canvas} minus its edge padding, reports the chosen
	 * multiplier to {@code onAppliedZoom} if non-null, and returns the resulting pack/card/gap pixel sizes.
	 */
	static ViewportLayout computeViewportLayout(Rectangle canvas, int cardCount, PackRevealService.Phase phase,
		double preferredZoomMul, DoubleConsumer onAppliedZoom)
	{
		int edge = viewportEdgePad(canvas);
		int availW = Math.max(80, canvas.width - 2 * edge);
		int availH = Math.max(80, canvas.height - 2 * edge);
		boolean packOnly = isPackSizedPhase(phase);
		double zoomMul = PackRevealZoomUtil.largestFittingAtMost(preferredZoomMul,
			level -> nativeLayoutFits(availW, availH, cardCount, packOnly, level));
		if (onAppliedZoom != null)
		{
			onAppliedZoom.accept(zoomMul);
		}
		return new ViewportLayout(
			PackRevealZoomUtil.scalePx(NATIVE_PACK_W, zoomMul),
			PackRevealZoomUtil.scalePx(NATIVE_PACK_H, zoomMul),
			PackRevealZoomUtil.scalePx(NATIVE_CARD_W, zoomMul),
			PackRevealZoomUtil.scalePx(NATIVE_CARD_H, zoomMul),
			PackRevealZoomUtil.scalePx(NATIVE_CARD_GAP, zoomMul));
	}

	/** True while the reveal is still showing the unopened pack, before individual card slots are laid out. */
	static boolean isPackSizedPhase(PackRevealService.Phase phase)
	{
		return phase == PackRevealService.Phase.PACK_READY
			|| phase == PackRevealService.Phase.PACK_FADING
			|| phase == PackRevealService.Phase.AWAITING_PULLS;
	}

	/**
	 * Lays out up to 2 cards on a top row and any remainder on a bottom row, both centered within
	 * {@code canvas}, using the card/gap sizes from {@code layout}. Returns an empty list for {@code count <= 0}.
	 */
	static List<Rectangle> layoutCardSlots(Rectangle canvas, int count, ViewportLayout layout)
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

	/** Edge padding for {@code canvas}: {@link #VIEWPORT_EDGE_PAD} at minimum, scaling down to 1/40 of the short side. */
	static int viewportEdgePad(Rectangle canvas)
	{
		if (canvas == null)
		{
			return VIEWPORT_EDGE_PAD;
		}
		int shortSide = Math.min(canvas.width, canvas.height);
		return Math.max(VIEWPORT_EDGE_PAD, Math.min(16, shortSide / 40));
	}

	/** Unscaled (base-size) grid width for {@code count} cards laid out as {@link #layoutCardSlots} would. */
	private static int naturalGridWidth(int count)
	{
		return naturalGridWidthWithSize(count, BASE_CARD_W, BASE_CARD_GAP);
	}

	/** Unscaled (base-size) grid height for {@code count} cards laid out as {@link #layoutCardSlots} would. */
	private static int naturalGridHeight(int count)
	{
		return naturalGridHeightWithSize(count, BASE_CARD_H, BASE_CARD_GAP);
	}

	/** Grid width for {@code count} cards at the given card width/gap: up to 2 cards per row, rows centered. */
	private static int naturalGridWidthWithSize(int count, int cardW, int gap)
	{
		if (count <= 0)
		{
			return 0;
		}
		int topCount = Math.min(2, count);
		int bottomCount = Math.max(0, count - topCount);
		int topWidth = (topCount * cardW) + (Math.max(0, topCount - 1) * gap);
		int bottomWidth = (bottomCount * cardW) + (Math.max(0, bottomCount - 1) * gap);
		return Math.max(topWidth, bottomWidth);
	}

	/** Grid height for {@code count} cards at the given card height/gap: one row, or two rows plus gap if a second row exists. */
	private static int naturalGridHeightWithSize(int count, int cardH, int gap)
	{
		if (count <= 0)
		{
			return 0;
		}
		int topCount = Math.min(2, count);
		int bottomCount = Math.max(0, count - topCount);
		return (bottomCount > 0) ? (cardH * 2) + gap : cardH;
	}

	/**
	 * True if, at zoom multiplier {@code mul}, the pack alone ({@code packOnly}) or the pack plus the
	 * {@code cardCount}-card grid fits within {@code availW}x{@code availH}.
	 */
	private static boolean nativeLayoutFits(int availW, int availH, int cardCount, boolean packOnly, double mul)
	{
		int packW = PackRevealZoomUtil.scalePx(NATIVE_PACK_W, mul);
		int packH = PackRevealZoomUtil.scalePx(NATIVE_PACK_H, mul);
		if (packOnly)
		{
			return packW <= availW && packH <= availH;
		}
		int cardW = PackRevealZoomUtil.scalePx(NATIVE_CARD_W, mul);
		int cardH = PackRevealZoomUtil.scalePx(NATIVE_CARD_H, mul);
		int gap = PackRevealZoomUtil.scalePx(NATIVE_CARD_GAP, mul);
		int gridW = naturalGridWidthWithSize(cardCount, cardW, gap);
		int gridH = naturalGridHeightWithSize(cardCount, cardH, gap);
		int needW = Math.max(packW, gridW);
		int needH = Math.max(packH, gridH);
		return needW <= availW && needH <= availH;
	}

	/**
	 * Computes {@link #NATIVE_LAYOUT_SCALE}: the scale factor applied to base sizes so the reference
	 * "classic" canvas size fits the max-visible-card grid, clamped to [{@link #MIN_OVERLAY_SCALE}, 1.0].
	 */
	private static double classicNativeLayoutScale()
	{
		Rectangle canvas = new Rectangle(0, 0, CLASSIC_CANVAS_W, CLASSIC_CANVAS_H);
		int edge = viewportEdgePad(canvas);
		int availW = Math.max(80, canvas.width - 2 * edge);
		int availH = Math.max(80, canvas.height - 2 * edge);
		int gridW = naturalGridWidth(CLASSIC_REF_CARD_COUNT);
		int gridH = naturalGridHeight(CLASSIC_REF_CARD_COUNT);
		double needW = Math.max(BASE_PACK_W, gridW);
		double needH = Math.max(BASE_PACK_H, gridH);
		double scaleW = availW / needW;
		double scaleH = availH / needH;
		double containS = Math.min(scaleW, scaleH);
		double fitS = defaultFitScale(canvas, scaleH, containS);
		return Math.max(MIN_OVERLAY_SCALE, Math.min(1.0d, fitS));
	}

	/** On short canvases, fits to height alone (may crop width); otherwise uses the contain scale for both axes. */
	private static double defaultFitScale(Rectangle canvas, double scaleH, double containS)
	{
		if (canvas != null && canvas.height <= SMALL_CANVAS_HEIGHT_PX)
		{
			return scaleH;
		}
		return containS;
	}

	/** Resolved pixel sizes (at the currently applied zoom) for the pack, a card, and the gap between cards. */
	static final class ViewportLayout
	{
		final int packW;
		final int packH;
		final int cardW;
		final int cardH;
		final int gap;

		/** Stores the resolved pixel sizes verbatim. */
		ViewportLayout(int packW, int packH, int cardW, int cardH, int gap)
		{
			this.packW = packW;
			this.packH = packH;
			this.cardW = cardW;
			this.cardH = cardH;
			this.gap = gap;
		}

		/** Returns the pack's rect, sized per this layout and centered within {@code canvas}. */
		Rectangle packRect(Rectangle canvas)
		{
			int x = canvas.x + (canvas.width - packW) / 2;
			int y = canvas.y + (canvas.height - packH) / 2;
			return new Rectangle(x, y, packW, packH);
		}
	}
}

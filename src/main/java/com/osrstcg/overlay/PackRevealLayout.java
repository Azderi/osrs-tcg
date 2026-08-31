package com.osrstcg.overlay;

import com.osrstcg.pack.PackRevealService;
import com.osrstcg.ui.SharedCardRenderer;
import com.osrstcg.util.PackRevealZoomUtil;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

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

	private PackRevealLayout()
	{
	}

	static ViewportLayout computeViewportLayout(Rectangle canvas, int cardCount, PackRevealService.Phase phase,
		double preferredZoomMul, DoubleConsumer onAppliedZoom)
	{
		ZoomMetrics m = measureZoom(canvas, cardCount, phase, preferredZoomMul, onAppliedZoom);
		return new ViewportLayout(m.packW, m.packH, m.cardW, m.cardH, m.gap);
	}

	static ZoomMetrics measureZoom(Rectangle canvas, int cardCount, PackRevealService.Phase phase,
		double preferredZoomMul, DoubleConsumer onAppliedZoom)
	{
		int edge = viewportEdgePad(canvas);
		int availW = Math.max(80, canvas.width - 2 * edge);
		int availH = Math.max(80, canvas.height - 2 * edge);
		int gridW = naturalGridWidth(cardCount);
		int gridH = naturalGridHeight(cardCount);
		boolean packOnly = isPackSizedPhase(phase);
		double needW = packOnly ? BASE_PACK_W : Math.max(BASE_PACK_W, gridW);
		double needH = packOnly ? BASE_PACK_H : Math.max(BASE_PACK_H, gridH);
		double scaleW = availW / needW;
		double scaleH = availH / needH;
		double containS = Math.min(scaleW, scaleH);
		double coverS = Math.max(scaleW, scaleH);
		double fitS = defaultFitScale(canvas, scaleH, containS);
		fitS = Math.max(MIN_OVERLAY_SCALE, Math.min(1.0d, fitS));

		double zoomMul = PackRevealZoomUtil.largestFittingAtMost(preferredZoomMul,
			level -> nativeLayoutFits(availW, availH, cardCount, packOnly, level));
		if (onAppliedZoom != null)
		{
			onAppliedZoom.accept(zoomMul);
		}

		double s = NATIVE_LAYOUT_SCALE * zoomMul;
		int packW = PackRevealZoomUtil.scalePx(NATIVE_PACK_W, zoomMul);
		int packH = PackRevealZoomUtil.scalePx(NATIVE_PACK_H, zoomMul);
		int cardW = PackRevealZoomUtil.scalePx(NATIVE_CARD_W, zoomMul);
		int cardH = PackRevealZoomUtil.scalePx(NATIVE_CARD_H, zoomMul);
		int gap = PackRevealZoomUtil.scalePx(NATIVE_CARD_GAP, zoomMul);
		return new ZoomMetrics(zoomMul, s, fitS, containS, coverS, scaleW, scaleH, packW, packH, cardW, cardH, gap);
	}

	static boolean isPackSizedPhase(PackRevealService.Phase phase)
	{
		return phase == PackRevealService.Phase.PACK_READY
			|| phase == PackRevealService.Phase.PACK_FADING
			|| phase == PackRevealService.Phase.AWAITING_PULLS;
	}

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

	static int viewportEdgePad(Rectangle canvas)
	{
		if (canvas == null)
		{
			return VIEWPORT_EDGE_PAD;
		}
		int shortSide = Math.min(canvas.width, canvas.height);
		return Math.max(VIEWPORT_EDGE_PAD, Math.min(16, shortSide / 40));
	}

	private static int naturalGridWidth(int count)
	{
		return naturalGridWidthWithSize(count, BASE_CARD_W, BASE_CARD_GAP);
	}

	private static int naturalGridHeight(int count)
	{
		return naturalGridHeightWithSize(count, BASE_CARD_H, BASE_CARD_GAP);
	}

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

	private static double defaultFitScale(Rectangle canvas, double scaleH, double containS)
	{
		if (canvas != null && canvas.height <= SMALL_CANVAS_HEIGHT_PX)
		{
			return scaleH;
		}
		return containS;
	}

	static final class ViewportLayout
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

	static final class ZoomMetrics
	{
		final double zoomMul;
		final double appliedS;
		final double fitS;
		final double containS;
		final double coverS;
		final double scaleW;
		final double scaleH;
		final int packW;
		final int packH;
		final int cardW;
		final int cardH;
		final int gap;

		ZoomMetrics(double zoomMul, double appliedS, double fitS, double containS, double coverS,
			double scaleW, double scaleH, int packW, int packH, int cardW, int cardH, int gap)
		{
			this.zoomMul = zoomMul;
			this.appliedS = appliedS;
			this.fitS = fitS;
			this.containS = containS;
			this.coverS = coverS;
			this.scaleW = scaleW;
			this.scaleH = scaleH;
			this.packW = packW;
			this.packH = packH;
			this.cardW = cardW;
			this.cardH = cardH;
			this.gap = gap;
		}
	}
}

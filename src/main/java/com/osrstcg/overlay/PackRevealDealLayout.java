package com.osrstcg.overlay;

import com.osrstcg.overlay.PackRevealLayout.ViewportLayout;
import com.osrstcg.pack.PackRevealService;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deal-phase card rects: pile wait, flight lerp, then landed slots. */
final class PackRevealDealLayout
{
	static final int DEAL_STACK_STEP = 5;

	private PackRevealDealLayout()
	{
	}

	static List<Rectangle> layoutDealPhaseCardRects(Rectangle canvas, ViewportLayout layout, int cardCount, long elapsed)
	{
		List<Rectangle> out = new ArrayList<>(cardCount);
		if (cardCount <= 0)
		{
			return out;
		}
		long stagger = PackRevealService.PACK_DEAL_STAGGER_MS;
		long flight = PackRevealService.PACK_DEAL_FLIGHT_MS;
		List<Rectangle> slots = PackRevealLayout.layoutCardSlots(canvas, cardCount, layout);
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
	static int dealDrawLayer(long elapsed, int i, long stagger, long flight)
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

	static Rectangle lerp(Rectangle from, Rectangle to, double t)
	{
		int x = (int) Math.round(from.x + ((to.x - from.x) * t));
		int y = (int) Math.round(from.y + ((to.y - from.y) * t));
		int w = (int) Math.round(from.width + ((to.width - from.width) * t));
		int h = (int) Math.round(from.height + ((to.height - from.height) * t));
		return new Rectangle(x, y, w, h);
	}

	static double clamp01(double v)
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

	static double smoothStep(double t)
	{
		t = clamp01(t);
		return t * t * (3.0d - 2.0d * t);
	}

	static Rectangle unionBounds(List<Rectangle> rects)
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
}

package com.osrstcg.overlay;

import com.osrstcg.overlay.PackRevealLayout.ViewportLayout;
import com.osrstcg.pack.PackRevealService;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * Layout math for the card-dealing animation phase: computes where each card sits mid-flight from
 * the pile to its final grid slot, and in what draw order. Pure functions over the caller-supplied
 * elapsed time, so it carries no mutable state and is safe to call from any thread.
 */
public final class PackRevealDealLayout
{
/** Pixel offset applied per waiting card in the still-stacked pile, so later cards peek out from under earlier ones. */
	static final int DEAL_STACK_STEP = 5;
/** Not instantiable; all members are static. */
	private PackRevealDealLayout()
	{
	}
/**
	 * Computes the current on-screen rectangle for every card during the deal phase: waiting cards are
	 * stacked at the pile center with a small offset per rank, in-flight cards are eased from the pile
	 * to their destination slot, and arrived cards sit at their final grid slot.
	 */
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
/**
	 * Draw-order layer for card {@code i} at the given elapsed time: 0 once it has arrived (drawn first,
	 * i.e. behind), 2 while in flight (drawn on top), 1 while still waiting in the pile.
	 */
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
/** Linearly interpolates position and size between two rectangles at {@code t} (0 = from, 1 = to). */
	static Rectangle lerp(Rectangle from, Rectangle to, double t)
	{
		int x = (int) Math.round(from.x + ((to.x - from.x) * t));
		int y = (int) Math.round(from.y + ((to.y - from.y) * t));
		int w = (int) Math.round(from.width + ((to.width - from.width) * t));
		int h = (int) Math.round(from.height + ((to.height - from.height) * t));
		return new Rectangle(x, y, w, h);
	}
/** Clamps {@code v} to the [0, 1] range. */
	public static double clamp01(double v)
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
/** Smoothstep easing (3t^2 - 2t^3) over {@code t}, clamped to [0, 1] first. */
	static double smoothStep(double t)
	{
		t = clamp01(t);
		return t * t * (3.0d - 2.0d * t);
	}
/** Bounding rectangle covering every rect in the list, or an empty rectangle at the origin if the list is null/empty. */
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
/**
	 * Per-card rectangle for the deal phase: the destination slot once arrived; a pile-stack position,
	 * offset by rank among still-waiting cards, before its stagger delay elapses; otherwise an eased
	 * lerp from the pile center to the destination slot.
	 */
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

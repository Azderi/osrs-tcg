package com.osrstcg.ui.tip;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.ui.card.CardGrade;
import com.osrstcg.util.CardDisplayNames;
import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;

/**
 * Data and layout math for the card-hover tooltip: builds the label/value/action rows for a card and
 * computes where the tooltip should sit relative to the cursor or canvas. Pure/static; no Swing state
 * of its own — {@link CardInfoTipPainter} does the actual rendering.
 */
public final class CardInfoTipModel
{
	public static final int DELAY_MS = 180;
	public static final int OFFSET_PX = 14;
	public static final int CLAMP_PAD_PX = 8;
	public static final int FADE_IN_MS = 160;

	public static final String ACTION_INSPECT = "inspect";
	public static final String ACTION_OPEN_WIKI = "open-wiki";

	/** One tooltip line: either a label/value detail pair, or a clickable action (when {@link #actionId} is set). */
	@Value
	public static class Row
	{
		String label;
		String value;
		String actionId;
		Color valueColor;

		/** Detail row with default value color. */
		public Row(String label, String value)
		{
			this(label, value, null, null);
		}

		/** Detail row with an explicit value color. */
		public Row(String label, String value, Color valueColor)
		{
			this(label, value, null, valueColor);
		}

		private Row(String label, String value, String actionId, Color valueColor)
		{
			this.label = label == null ? "" : label;
			this.value = value == null ? "" : value;
			this.actionId = actionId == null || actionId.isBlank() ? null : actionId;
			this.valueColor = valueColor;
		}

		/** Creates a clickable action row (no value) identified by {@code actionId}. */
		public static Row action(String label, String actionId)
		{
			return new Row(label, "", actionId, null);
		}

		/** @return true if this is a clickable action row rather than a detail row. */
		public boolean isAction()
		{
			return actionId != null;
		}
	}

	/** Immutable tooltip content: a title plus an ordered list of {@link Row}s. */
	@Value
	public static class Content
	{
		String title;
		List<Row> rows;

		public Content(String title, List<Row> rows)
		{
			this.title = title == null || title.isBlank() ? "Card" : title;
			this.rows = Collections.unmodifiableList(new ArrayList<>(rows == null ? List.of() : rows));
		}
	}

	private CardInfoTipModel()
	{
	}

	/**
	 * Positions the tooltip near the cursor, flipping to the opposite side when it would overflow the
	 * canvas edge, then clamps fully inside the canvas (padded by {@link #CLAMP_PAD_PX}).
	 */
	public static Point position(int cursorX, int cursorY, int tipW, int tipH, int canvasW, int canvasH)
	{
		int w = Math.max(1, tipW);
		int h = Math.max(1, tipH);
		int pad = CLAMP_PAD_PX;
		int left = cursorX + OFFSET_PX;
		int top = cursorY + OFFSET_PX;
		if (left + w > canvasW - pad)
		{
			left = cursorX - w - OFFSET_PX;
		}
		if (top + h > canvasH - pad)
		{
			top = cursorY - h - OFFSET_PX;
		}
		left = Math.max(pad, Math.min(left, canvasW - w - pad));
		top = Math.max(pad, Math.min(top, canvasH - h - pad));
		return new Point(left, top);
	}

	/** Positions the tooltip pinned to the canvas's top-right corner (padded), used when no cursor position applies. */
	public static Point topRight(int tipW, int tipH, int canvasW, int canvasH)
	{
		int w = Math.max(1, tipW);
		int h = Math.max(1, tipH);
		int pad = CLAMP_PAD_PX;
		int left = Math.max(pad, canvasW - w - pad);
		int top = pad;
		if (top + h > canvasH - pad)
		{
			top = Math.max(pad, canvasH - h - pad);
		}
		return new Point(left, top);
	}

	/** {@link #forPackRevealCard(PackRevealService.RevealCard, boolean)} without action rows. */
	public static Content forPackRevealCard(PackRevealService.RevealCard card)
	{
		return forPackRevealCard(card, false);
	}

	/**
	 * Builds tooltip content for a pack-reveal card: title, grade/condition row, artist row (when the
	 * card has a foil image and artist name), and optionally "Inspect"/"Open wiki page" action rows.
	 */
	public static Content forPackRevealCard(PackRevealService.RevealCard card, boolean includeContextActions)
	{
		if (card == null)
		{
			return new Content("Card", List.of());
		}
		PackCardResult pull = card.getPull();
		CardDefinition def = card.getDefinition();
		String title = tipTitle(def, pull);
		Double condition = pull == null ? null : pull.getCondition();
		List<Row> rows = packRevealRows(condition);
		appendArtistRow(rows, def);
		if (includeContextActions)
		{
			String instanceId = instanceIdFor(card);
			if (instanceId != null)
			{
				rows.add(Row.action("Inspect", ACTION_INSPECT));
			}
			String wiki = wikiPageFor(card);
			if (wiki != null)
			{
				rows.add(Row.action("Open wiki page", ACTION_OPEN_WIKI));
			}
		}
		return new Content(title, rows);
	}

	/** Appends an "Artist" row if the definition has both a foil image and a non-blank artist name. */
	static void appendArtistRow(List<Row> rows, CardDefinition def)
	{
		if (rows == null || def == null)
		{
			return;
		}
		String foilPath = def.getFoilImagePath() == null ? "" : def.getFoilImagePath().trim();
		if (foilPath.isEmpty())
		{
			return;
		}
		String name = def.getArtistName() == null ? "" : def.getArtistName().trim();
		if (name.isEmpty())
		{
			return;
		}
		rows.add(new Row("Artist", name, normalizeArtistColor(def.getArtistColor())));
	}

	/** Parses a {@code #RRGGBB} or {@code #RGB} hex string into a {@link Color}; returns {@code null} if unparsable. */
	static Color normalizeArtistColor(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String s = raw.trim();
		if (s.length() == 7 && s.charAt(0) == '#')
		{
			try
			{
				return Color.decode(s.toUpperCase());
			}
			catch (NumberFormatException ignored)
			{
				return null;
			}
		}
		if (s.length() == 4 && s.charAt(0) == '#')
		{
			char r = s.charAt(1);
			char g = s.charAt(2);
			char b = s.charAt(3);
			try
			{
				return Color.decode(("#" + r + r + g + g + b + b).toUpperCase());
			}
			catch (NumberFormatException ignored)
			{
				return null;
			}
		}
		return null;
	}

	/** @return the pulled card's instance id, or {@code null} if the card has no pull or no id. */
	public static String instanceIdFor(PackRevealService.RevealCard card)
	{
		if (card == null)
		{
			return null;
		}
		PackCardResult pull = card.getPull();
		if (pull != null && pull.getInstanceId() != null && !pull.getInstanceId().isBlank())
		{
			return pull.getInstanceId().trim();
		}
		return null;
	}

	/** @return the wiki page for this card, preferring the pull's own page over the card definition's. */
	public static String wikiPageFor(PackRevealService.RevealCard card)
	{
		if (card == null)
		{
			return null;
		}
		PackCardResult pull = card.getPull();
		if (pull != null && pull.getWikiPage() != null && !pull.getWikiPage().isBlank())
		{
			return pull.getWikiPage().trim();
		}
		if (card.getDefinition() != null && card.getDefinition().getWikiPage() != null
			&& !card.getDefinition().getWikiPage().isBlank())
		{
			return card.getDefinition().getWikiPage().trim();
		}
		return null;
	}

	/** Builds a single "Grade"/"Condition" row from a pull's condition value, preferring the combined grade+condition form. */
	static List<Row> packRevealRows(Double condition)
	{
		List<Row> rows = new ArrayList<>();
		CardGrade grade = CardGrade.gradeFromCondition(condition);
		String conditionLabel = CardGrade.formatCondition(condition);
		if (grade != null && conditionLabel != null)
		{
			rows.add(new Row("Grade", grade.name() + " (" + conditionLabel + ")"));
		}
		else if (grade != null)
		{
			rows.add(new Row("Grade", grade.name()));
		}
		else if (conditionLabel != null)
		{
			rows.add(new Row("Condition", conditionLabel));
		}
		return rows;
	}

	/** @return the tooltip title for a card definition/pull pair. */
	static String tipTitle(CardDefinition def, PackCardResult pull)
	{
		return CardDisplayNames.titleForDefinition(def, pull);
	}
}

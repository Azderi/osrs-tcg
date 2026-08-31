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

public final class CardInfoTipModel
{
	public static final int DELAY_MS = 180;
	public static final int OFFSET_PX = 14;
	public static final int CLAMP_PAD_PX = 8;
	public static final int FADE_IN_MS = 160;

	public static final String ACTION_OPEN_WIKI = "open-wiki";

	@Value
	public static class Row
	{
		String label;
		String value;
		String actionId;
		Color valueColor;

		public Row(String label, String value)
		{
			this(label, value, null, null);
		}

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

		public static Row action(String label, String actionId)
		{
			return new Row(label, "", actionId, null);
		}

		public boolean isAction()
		{
			return actionId != null;
		}
	}

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

	public static Content forPackRevealCard(PackRevealService.RevealCard card)
	{
		return forPackRevealCard(card, false);
	}

	public static Content forPackRevealCard(PackRevealService.RevealCard card, boolean includeContextMenuActions)
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
		if (includeContextMenuActions)
		{
			String wiki = wikiPageFor(card);
			if (wiki != null)
			{
				rows.add(Row.action("Open wiki page", ACTION_OPEN_WIKI));
			}
		}
		return new Content(title, rows);
	}

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

	static String tipTitle(CardDefinition def, PackCardResult pull)
	{
		return CardDisplayNames.titleForDefinition(def, pull);
	}
}

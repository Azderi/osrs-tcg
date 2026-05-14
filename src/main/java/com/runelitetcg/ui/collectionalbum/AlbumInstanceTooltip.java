package com.runelitetcg.ui.collectionalbum;

import com.runelitetcg.model.OwnedCardInstance;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

public final class AlbumInstanceTooltip
{
	private AlbumInstanceTooltip()
	{
	}

	public static String format(OwnedCardInstance o)
	{
		if (o == null)
		{
			return null;
		}
		String by = o.getPulledByUsername() == null ? "" : o.getPulledByUsername().trim();
		long at = o.getPulledAtEpochMs();
		StringBuilder sb = new StringBuilder();
		if (!by.isEmpty())
		{
			sb.append("Pulled by: ").append(by);
		}
		if (at > 0L)
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			String when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
				.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
			sb.append(when);
		}
		return sb.length() == 0 ? null : sb.toString();
	}
}

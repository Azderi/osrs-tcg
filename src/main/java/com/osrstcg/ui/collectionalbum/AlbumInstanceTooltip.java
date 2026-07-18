package com.osrstcg.ui.collectionalbum;

import com.osrstcg.model.OwnedCardInstance;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

public final class AlbumInstanceTooltip
{
	public static final String LOCKED_ACTION_HINT =
		"This card is locked. Right-click it to unlock before selling or sending.";

	private AlbumInstanceTooltip()
	{
	}

	public static String format(OwnedCardInstance o)
	{
		if (o == null)
		{
			return null;
		}
		return format(o.getPulledByUsername(), o.getPulledAtEpochMs(), o.isLocked());
	}

	public static String format(String pulledByUsername, long pulledAtEpochMs, boolean locked)
	{
		String displayBy = OwnedCardInstance.formatPulledByForUi(pulledByUsername);
		long at = Math.max(0L, pulledAtEpochMs);
		StringBuilder sb = new StringBuilder();
		if (!displayBy.isEmpty())
		{
			sb.append("Pulled by: ").append(displayBy);
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
		if (locked)
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append("Locked");
		}
		return sb.length() == 0 ? null : sb.toString();
	}
}

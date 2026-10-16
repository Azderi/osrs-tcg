package com.osrstcg.notify;

import com.osrstcg.OsrsTcgConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PullNotifySupportTest
{
	@Test
	public void dinkCollectionSummaryDefaultsToEnabled()
	{
		assertTrue(new OsrsTcgConfig() { }.includeDinkCollectionSummary());
	}

	@Test
	public void enabledDinkCollectionSummaryAppendsStatsAfterBlankLine()
	{
		PullNotifySupport support = supportWithStats(new OsrsTcgConfig() { }, "Collection stats");

		assertEquals("Pull notification\n\nCollection stats",
			support.messageWithStatsLine("Pull notification"));
	}

	@Test
	public void disabledDinkCollectionSummaryLeavesMessageUnchanged()
	{
		OsrsTcgConfig config = new OsrsTcgConfig()
		{
			@Override
			public boolean includeDinkCollectionSummary()
			{
				return false;
			}
		};
		PullNotifySupport support = supportWithStats(config, "Collection stats");

		assertEquals("Pull notification", support.messageWithStatsLine("Pull notification"));
	}

	private static PullNotifySupport supportWithStats(OsrsTcgConfig config, String statsLine)
	{
		return new PullNotifySupport(config, null, null, null)
		{
			@Override
			public String statsPlainLine()
			{
				return statsLine;
			}
		};
	}
}

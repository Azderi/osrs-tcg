package com.osrstcg.notify;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PullNotificationMessagesTest
{
	@Test
	public void packSummaryOmitsEmptyDuplicatesSection()
	{
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**New cards**\n- **Zilyana**\n- Goblin",
			PullNotificationMessages.dinkPackSummaryMessage(
				Arrays.asList("**Zilyana**", "Goblin"),
				Collections.emptyList()));
	}

	@Test
	public void packSummaryOmitsEmptyNewCardsSection()
	{
		assertEquals(
			"%USERNAME% opened a booster pack!\n\n**Duplicates**\n- **General Graardor**",
			PullNotificationMessages.dinkPackSummaryMessage(
				Collections.emptyList(),
				Collections.singletonList("**General Graardor**")));
	}

	@Test
	public void packSummaryContainsOnlyOpeningLineWhenBothSectionsAreEmpty()
	{
		assertEquals(
			"%USERNAME% opened a booster pack!",
			PullNotificationMessages.dinkPackSummaryMessage(
				Collections.emptyList(),
				Collections.emptyList()));
	}
}

package com.osrstcg.util;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * Detects game interface widgets that should affect plugin behavior.
 * <p>
 * Must be called on the RuneLite client thread.
 */
public final class GameWidgetUtil
{
	/** {@code WelcomeScreen.UNIVERSE} - group 378, child 0. */
	private static final int WELCOME_SCREEN_GROUP = 378;
	private static final int WELCOME_SCREEN_CHILD = 0;

	private GameWidgetUtil()
	{
	}

	public static boolean isWelcomeScreenVisible(Client client)
	{
		if (client == null)
		{
			return false;
		}

		Widget widget = client.getWidget(WELCOME_SCREEN_GROUP, WELCOME_SCREEN_CHILD);
		return widget != null && !widget.isHidden();
	}
}

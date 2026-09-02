package com.osrstcg.overlay;

import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.credit.CreditsRateTracker;
import com.osrstcg.pack.PackOpenCoordinator;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.ui.overlay.OverlayMenuEntry;

/**
 * Credits-infobox overlay menus: open pack and reset credits/h.
 */
@Singleton
public class CreditsInfoboxMenuHandler
{
	private final CreditsInfoboxOverlay creditsInfoboxOverlay;
	private final CreditsRateTracker creditsRateTracker;
	private final PackCatalogService packCatalogService;
	private final PackOpenCoordinator packOpenCoordinator;
	private final ClientThread clientThread;

	/** Wires the collaborators needed to match clicked menu entries back to boosters and open them. */
	@Inject
	public CreditsInfoboxMenuHandler(
		CreditsInfoboxOverlay creditsInfoboxOverlay,
		CreditsRateTracker creditsRateTracker,
		PackCatalogService packCatalogService,
		PackOpenCoordinator packOpenCoordinator,
		ClientThread clientThread)
	{
		this.creditsInfoboxOverlay = creditsInfoboxOverlay;
		this.creditsRateTracker = creditsRateTracker;
		this.packCatalogService = packCatalogService;
		this.packOpenCoordinator = packOpenCoordinator;
		this.clientThread = clientThread;
	}

	/**
	 * Handles a click on one of {@link CreditsInfoboxOverlay}'s menu entries: resets the credits/h
	 * tracker, or matches the clicked target back to a visible booster and opens it.
	 */
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		if (event.getOverlay() != creditsInfoboxOverlay)
		{
			return;
		}

		OverlayMenuEntry entry = event.getEntry();
		if (entry == null)
		{
			return;
		}

		if (CreditsInfoboxOverlay.MENU_OPTION_RESET.equals(entry.getOption())
			&& CreditsInfoboxOverlay.MENU_TARGET_CREDITS_PER_HOUR.equals(entry.getTarget()))
		{
			creditsRateTracker.clear();
			return;
		}

		if (!CreditsInfoboxOverlay.MENU_OPTION_OPEN.equals(entry.getOption()))
		{
			return;
		}

		String target = entry.getTarget();
		for (BoosterPackDefinition booster : packCatalogService.getVisibleBoosters())
		{
			if (CreditsInfoboxOverlay.packMenuTarget(booster).equals(target))
			{
				packOpenCoordinator.openFromPlugin(booster, clientThread::invokeLater);
				return;
			}
		}
	}
}

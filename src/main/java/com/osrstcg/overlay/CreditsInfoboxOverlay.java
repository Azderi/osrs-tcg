package com.osrstcg.overlay;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.credit.CreditsRateTracker;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.NumberFormatting;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.util.ImageUtil;

/**
 * Movable plain-text overlay for current credits and a configurable-window credits/h rate.
 * Shift+right-click lists affordable visible booster packs to buy and open, and Reset for Credits/h.
 */
@Singleton
public class CreditsInfoboxOverlay extends OverlayPanel
{
	public static final String MENU_OPTION_OPEN = "Open";
	public static final String MENU_OPTION_RESET = "Reset";
	public static final String MENU_TARGET_CREDITS_PER_HOUR = "Credits/h";

	private static final BufferedImage CREDIT_ICON = ImageUtil.resizeImage(
		ImageUtil.loadImageResource(CreditsInfoboxOverlay.class, "/com/osrstcg/images/credits.png"),
		21,
		16);

	private final OsrsTcgConfig config;
	private final TcgStateService stateService;
	private final CreditsRateTracker creditsRateTracker;
	private final PackCatalogService packCatalogService;

	private long lastCredits = Long.MIN_VALUE;
	private Long lastCreditsPerHour;
	private boolean lastShowRate;
	private boolean lastShowInfobox = true;
	private int lastMenuFingerprint = Integer.MIN_VALUE;

	@Inject
	CreditsInfoboxOverlay(
		OsrsTcgConfig config,
		TcgStateService stateService,
		CreditsRateTracker creditsRateTracker,
		PackCatalogService packCatalogService)
	{
		this.config = config;
		this.stateService = stateService;
		this.creditsRateTracker = creditsRateTracker;
		this.packCatalogService = packCatalogService;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.creditsInfobox())
		{
			if (lastShowInfobox)
			{
				getMenuEntries().clear();
				panelComponent.getChildren().clear();
				lastShowInfobox = false;
				lastCredits = Long.MIN_VALUE;
				lastMenuFingerprint = Integer.MIN_VALUE;
			}
			return null;
		}
		lastShowInfobox = true;

		long credits = stateService.getCredits();
		boolean showRate = config.creditsPerHour();
		Long creditsPerHour = showRate ? creditsRateTracker.creditsPerHourOrNull() : null;
		int menuFingerprint = menuFingerprint(credits, showRate);
		boolean valuesChanged = credits != lastCredits
			|| showRate != lastShowRate
			|| !java.util.Objects.equals(creditsPerHour, lastCreditsPerHour);
		if (valuesChanged)
		{
			lastCredits = credits;
			lastShowRate = showRate;
			lastCreditsPerHour = creditsPerHour;
			panelComponent.getChildren().clear();
			panelComponent.getChildren().add(SplitComponent.builder()
				.orientation(ComponentOrientation.HORIZONTAL)
				.gap(new Point(4, 0))
				.first(new ImageComponent(CREDIT_ICON))
				.second(LineComponent.builder()
					.right(NumberFormatting.format(credits))
					.build())
				.build());

			if (showRate && creditsPerHour != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.right(NumberFormatting.format(creditsPerHour) + "/h")
					.build());
			}
		}
		if (menuFingerprint != lastMenuFingerprint)
		{
			lastMenuFingerprint = menuFingerprint;
			refreshMenuEntries();
		}

		return super.render(graphics);
	}

	private int menuFingerprint(long credits, boolean showRate)
	{
		int hash = showRate ? 1 : 0;
		hash = 31 * hash + Long.hashCode(credits);
		for (BoosterPackDefinition booster : packCatalogService.getVisibleBoosters())
		{
			if (booster == null || credits < booster.getPrice())
			{
				continue;
			}
			String target = packMenuTarget(booster);
			hash = 31 * hash + target.hashCode();
			hash = 31 * hash + Integer.hashCode(booster.getPrice());
		}
		return hash;
	}

	/** Display name used as the overlay menu target for a pack. */
	public static String packMenuTarget(BoosterPackDefinition booster)
	{
		if (booster == null)
		{
			return "";
		}
		if (booster.getName() != null && !booster.getName().isBlank())
		{
			return booster.getName();
		}
		if (booster.getId() != null && !booster.getId().isBlank())
		{
			return booster.getId();
		}
		return "Pack";
	}

	private void refreshMenuEntries()
	{
		getMenuEntries().clear();
		if (config.creditsPerHour())
		{
			addMenuEntry(MenuAction.RUNELITE_OVERLAY, MENU_OPTION_RESET, MENU_TARGET_CREDITS_PER_HOUR);
		}
		long credits = stateService.getCredits();
		for (BoosterPackDefinition booster : packCatalogService.getVisibleBoosters())
		{
			if (booster == null || credits < booster.getPrice())
			{
				continue;
			}
			addMenuEntry(MenuAction.RUNELITE_OVERLAY, MENU_OPTION_OPEN, packMenuTarget(booster));
		}
	}
}

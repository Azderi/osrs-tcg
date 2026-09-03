package com.osrstcg.pack;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.shop.CloudPackService;
import com.osrstcg.notify.PullNotificationService;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CollectionState;
import com.osrstcg.state.PackOpenResult;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.SidebarRefresh;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import net.runelite.client.chat.ChatMessageManager;

/**
 * Shared pending-reveal + {@code buyAndOpenPack} sequence used by the infobox, {@code ::tcg-open},
 * and the shop buy button.
 */
@Singleton
public class PackOpenCoordinator
{
	private final PackRevealService packRevealService;
	private final CloudPackService cloudPackService;
	private final PackCatalogService packCatalogService;
	private final PullNotificationService pullNotificationService;
	private final TcgStateService stateService;
	private final CloudSessionService cloudSessionService;
	private final Provider<SidebarRefresh> sidebarRefreshProvider;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final OsrsTcgConfig config;

	/** Wires the collaborators used to buy packs, run the reveal state machine, and refresh the UI afterwards. */
	@Inject
	public PackOpenCoordinator(
		PackRevealService packRevealService,
		CloudPackService cloudPackService,
		PackCatalogService packCatalogService,
		PullNotificationService pullNotificationService,
		TcgStateService stateService,
		CloudSessionService cloudSessionService,
		Provider<SidebarRefresh> sidebarRefreshProvider,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		OsrsTcgConfig config)
	{
		this.packRevealService = packRevealService;
		this.cloudPackService = cloudPackService;
		this.packCatalogService = packCatalogService;
		this.pullNotificationService = pullNotificationService;
		this.stateService = stateService;
		this.cloudSessionService = cloudSessionService;
		this.sidebarRefreshProvider = sidebarRefreshProvider;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.config = config;
	}

	/** Infobox / {@code ::tcg-open}: freeze sidebar, chat credits on success, resume on the client thread. */
	public void openFromPlugin(BoosterPackDefinition booster, Consumer<Runnable> invokeLater)
	{
		SidebarRefresh panel = sidebarRefreshProvider.get();
		open(booster, new UiHooks(
			true,
			true,
			null,
			panel::beginPackRevealSidebarFreeze,
			panel::clearPackRevealSidebarFreeze,
			panel::refresh,
			invokeLater));
	}

	/** Shop buy button: optional in-flight guard, resume on the EDT. */
	public void openFromShop(BoosterPackDefinition booster, AtomicBoolean inFlight, Runnable beginFreeze,
		Runnable clearFreeze, Runnable refresh, Consumer<Runnable> invokeLater)
	{
		open(booster, new UiHooks(
			false,
			false,
			inFlight,
			beginFreeze,
			clearFreeze,
			refresh,
			invokeLater));
	}

	/**
	 * Guards against concurrent opens, freezes the sidebar, snapshots pre-owned cards, begins the
	 * pending reveal, then buys/opens the pack on {@link #scheduler} and resumes on {@code ui.invokeLater}.
	 * Must be called on the client thread.
	 */
	private void open(BoosterPackDefinition booster, UiHooks ui)
	{
		if (booster == null)
		{
			return;
		}
		if (packRevealService.isActive() || (ui.inFlight != null && ui.inFlight.get()))
		{
			if (ui.chatWhenBusy)
			{
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Finish the current pack reveal first.");
			}
			ui.refresh.run();
			return;
		}
		if (ui.inFlight != null && !ui.inFlight.compareAndSet(false, true))
		{
			return;
		}

		ui.beginFreeze.run();
		CollectionState collection = stateService.getState().getCollectionState();
		HashSet<CardCollectionKey> preOwned = new HashSet<>(
			(config.ignoreBetaForNewStatus()
				? collection.getOwnedCardsExcludingBeta()
				: collection.getOwnedCards()).keySet());
		String boosterPackId = booster.getId() == null ? "" : booster.getId().trim();
		int expectedCards = Math.max(1, packCatalogService.getCache().getPackSize());
		packRevealService.beginPendingReveal(boosterPackId, false, expectedCards);
		ui.refresh.run();
		scheduler.execute(() ->
		{
			PackOpenResult result = cloudPackService.buyAndOpenPack(booster);
			ui.invokeLater.accept(() ->
			{
				try
				{
					applyOpenResult(result, preOwned, ui);
				}
				finally
				{
					if (ui.inFlight != null)
					{
						ui.inFlight.set(false);
					}
				}
			});
		});
	}

	/**
	 * Async continuation of {@link #open}, run on the UI thread after the buy/open call returns: unfreezes
	 * the sidebar and reports failure, or hands successful pulls to the reveal service and announces credits.
	 */
	private void applyOpenResult(PackOpenResult result, HashSet<CardCollectionKey> preOwned, UiHooks ui)
	{
		if (!packRevealService.isActive() && !packRevealService.isAwaitingServerPulls())
		{
			ui.clearFreeze.run();
			if (result.isSuccess() && result.getPulls() != null && !result.getPulls().isEmpty())
			{
				pullNotificationService.postAllCollectionAdds(result.getPulls(), preOwned);
			}
			ui.refresh.run();
			return;
		}
		if (!result.isSuccess() || result.getPulls() == null)
		{
			packRevealService.abortPendingReveal();
			ui.clearFreeze.run();
			if (!cloudSessionService.isAccountLocked()
				&& result.getMessage() != null && !result.getMessage().isEmpty())
			{
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, result.getMessage());
			}
			ui.refresh.run();
			return;
		}

		if (ui.announceCreditsOnSuccess)
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, String.format(Locale.US,
				"Opened pack for %s credits. New balance: %s. Pulled %s cards.",
				NumberFormatting.format(result.getPackPrice()), NumberFormatting.format(result.getCreditsAfter()),
				NumberFormatting.format(result.getPulls().size())));
		}
		if (!packRevealService.supplyRevealPulls(result.getPulls(), preOwned, result.isApexPack()))
		{
			packRevealService.abortPendingReveal();
			ui.clearFreeze.run();
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, "Pack open returned no cards.");
		}
		ui.refresh.run();
	}

	/** Caller-specific UI callbacks/flags that let {@link #open} and {@link #applyOpenResult} stay caller-agnostic. */
	private static final class UiHooks
	{
		final boolean announceCreditsOnSuccess;
		final boolean chatWhenBusy;
		final AtomicBoolean inFlight;
		final Runnable beginFreeze;
		final Runnable clearFreeze;
		final Runnable refresh;
		final Consumer<Runnable> invokeLater;

		/** Stores the caller-supplied flags and callbacks verbatim. */
		UiHooks(boolean announceCreditsOnSuccess, boolean chatWhenBusy,
			AtomicBoolean inFlight, Runnable beginFreeze, Runnable clearFreeze, Runnable refresh,
			Consumer<Runnable> invokeLater)
		{
			this.announceCreditsOnSuccess = announceCreditsOnSuccess;
			this.chatWhenBusy = chatWhenBusy;
			this.inFlight = inFlight;
			this.beginFreeze = beginFreeze;
			this.clearFreeze = clearFreeze;
			this.refresh = refresh;
			this.invokeLater = invokeLater;
		}
	}
}

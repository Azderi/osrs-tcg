package com.osrstcg.pack;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.ui.SidebarRefresh;
import com.osrstcg.util.GameWidgetUtil;
import com.osrstcg.util.TcgPluginGameMessages;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.eventbus.Subscribe;
import com.osrstcg.credit.PlayerCombatMonitor;

/**
 * When Safe-mode is enabled, blocks pack purchases during combat and closes an active pack reveal if combat starts.
 * Also exposes {@link #forceCloseActiveReveal(String)} for Esc (and similar) instant dismiss.
 */
@Singleton
public final class PackSafeModeService
{
	private final OsrsTcgConfig config;
	private final Client client;
	private final PlayerCombatMonitor combatMonitor;
	private final PackRevealService packRevealService;
	private final ChatMessageManager chatMessageManager;
	private final Provider<SidebarRefresh> sidebarRefreshProvider;

	private boolean combatStateLastTick;
	private boolean welcomeScreenVisibleLastTick;
	private volatile boolean welcomeScreenVisible;

	@Inject
	public PackSafeModeService(
		OsrsTcgConfig config,
		Client client,
		PlayerCombatMonitor combatMonitor,
		PackRevealService packRevealService,
		ChatMessageManager chatMessageManager,
		Provider<SidebarRefresh> sidebarRefreshProvider)
	{
		this.config = config;
		this.client = client;
		this.combatMonitor = combatMonitor;
		this.packRevealService = packRevealService;
		this.chatMessageManager = chatMessageManager;
		this.sidebarRefreshProvider = sidebarRefreshProvider;
	}

	public boolean isPackOpeningBlocked()
	{
		return isPackOpeningBlockedByWelcomeScreen() || isPackOpeningBlockedByCombat();
	}

	public boolean isPackOpeningBlockedByCombat()
	{
		return config.safeMode() && combatMonitor.isLocalPlayerInCombat();
	}

	public boolean isPackOpeningBlockedByWelcomeScreen()
	{
		return welcomeScreenVisible;
	}

	public String packOpeningBlockMessage()
	{
		if (isPackOpeningBlockedByWelcomeScreen())
		{
			return "Cannot open packs on the welcome screen.";
		}
		if (isPackOpeningBlockedByCombat())
		{
			return "Cannot open packs while in combat (Safe-mode).";
		}
		return null;
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!config.safeMode() || !combatMonitor.isLocalPlayerInCombat())
		{
			return;
		}
		maybeCloseRevealForCombat(true);
		sidebarRefreshProvider.get().refresh();
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!config.safeMode() || !combatMonitor.isLocalPlayerInCombat())
		{
			return;
		}
		maybeCloseRevealForCombat(true);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		boolean welcomeScreenVisibleNow = GameWidgetUtil.isWelcomeScreenVisible(client);
		welcomeScreenVisible = welcomeScreenVisibleNow;
		if (welcomeScreenVisibleNow != welcomeScreenVisibleLastTick)
		{
			welcomeScreenVisibleLastTick = welcomeScreenVisibleNow;
			sidebarRefreshProvider.get().refresh();
		}

		if (!config.safeMode())
		{
			if (combatStateLastTick)
			{
				combatStateLastTick = false;
				sidebarRefreshProvider.get().refresh();
			}
			return;
		}

		boolean inCombat = combatMonitor.isLocalPlayerInCombat();
		maybeCloseRevealForCombat(inCombat);

		if (inCombat != combatStateLastTick)
		{
			combatStateLastTick = inCombat;
			sidebarRefreshProvider.get().refresh();
		}
	}

	private void maybeCloseRevealForCombat(boolean inCombat)
	{
		if (inCombat && packRevealService.isActive())
		{
			forceCloseActiveReveal("Combat interrupted pack reveal - your cards are in your collection.");
		}
	}

	/**
	 * Instantly ends an active pack reveal (same path as Safe-mode combat interrupt):
	 * aborts the overlay (announcing every pulled card to chat), refreshes the sidebar.
	 */
	public void forceCloseActiveReveal(String reasonMessage)
	{
		if (!packRevealService.isActive())
		{
			return;
		}

		if (reasonMessage != null && !reasonMessage.isBlank())
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, reasonMessage);
		}

		packRevealService.abortActiveReveal();
		sidebarRefreshProvider.get().refreshAfterPackRevealClose();
	}
}

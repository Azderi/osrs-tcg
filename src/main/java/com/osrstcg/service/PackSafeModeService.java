package com.osrstcg.service;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.model.PackCardResult;
import com.osrstcg.service.PackRevealService.RevealCard;
import com.osrstcg.ui.TcgPanel;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * When Safe-mode is enabled, blocks pack purchases during combat and closes an active pack reveal if combat starts.
 */
@Singleton
public final class PackSafeModeService
{
	private final OsrsTcgConfig config;
	private final PlayerCombatMonitor combatMonitor;
	private final PackRevealService packRevealService;
	private final CardDatabase cardDatabase;
	private final ChatMessageManager chatMessageManager;
	private final Provider<TcgPanel> tcgPanelProvider;

	private boolean combatStateLastTick;

	@Inject
	public PackSafeModeService(
		OsrsTcgConfig config,
		PlayerCombatMonitor combatMonitor,
		PackRevealService packRevealService,
		CardDatabase cardDatabase,
		ChatMessageManager chatMessageManager,
		Provider<TcgPanel> tcgPanelProvider)
	{
		this.config = config;
		this.combatMonitor = combatMonitor;
		this.packRevealService = packRevealService;
		this.cardDatabase = cardDatabase;
		this.chatMessageManager = chatMessageManager;
		this.tcgPanelProvider = tcgPanelProvider;
	}

	public boolean isSafeModeEnabled()
	{
		return config.safeMode();
	}

	public boolean isPackOpeningBlocked()
	{
		return config.safeMode() && combatMonitor.isLocalPlayerInCombat();
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!config.safeMode() || !combatMonitor.isLocalPlayerInCombat())
		{
			return;
		}
		maybeCloseRevealForCombat(true);
		tcgPanelProvider.get().refresh();
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
		if (!config.safeMode())
		{
			if (combatStateLastTick)
			{
				combatStateLastTick = false;
				tcgPanelProvider.get().refresh();
			}
			return;
		}

		boolean inCombat = combatMonitor.isLocalPlayerInCombat();
		maybeCloseRevealForCombat(inCombat);

		if (inCombat != combatStateLastTick)
		{
			combatStateLastTick = inCombat;
			tcgPanelProvider.get().refresh();
		}
	}

	private void maybeCloseRevealForCombat(boolean inCombat)
	{
		if (inCombat && packRevealService.isActive())
		{
			closeRevealForCombat();
		}
	}

	private void closeRevealForCombat()
	{
		List<RevealCard> cards = packRevealService.abortActiveReveal();
		if (cards.isEmpty())
		{
			return;
		}

		TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager,
			"Combat interrupted pack reveal — your cards are in your collection.");

		for (RevealCard card : cards)
		{
			if (card == null || card.getPull() == null)
			{
				continue;
			}
			PackCardResult pull = card.getPull();
			String name = pull.getCardName();
			if (name == null || name.trim().isEmpty())
			{
				continue;
			}
			Color rarity = cardDatabase.chatRarityColorForCardName(name);
			String formatted = TcgPluginGameMessages.formatGoldPrefixedYouPulled(name, pull.isFoil(), rarity);
			String plain = TcgPluginGameMessages.plainGoldPrefixedYouPulled(name, pull.isFoil());
			TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
		}

		tcgPanelProvider.get().refreshAfterPackRevealClose();
	}
}

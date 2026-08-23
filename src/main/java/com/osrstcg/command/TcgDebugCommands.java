package com.osrstcg.command;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.CollectionSetCompletionUtil;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.cloud.catalog.CardCatalogService;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.session.CloudSessionCoordinator;
import com.osrstcg.overlay.CreditsInfoboxOverlay;
import com.osrstcg.pack.PackOpenCoordinator;
import com.osrstcg.party.TcgPartyAnnouncer;
import com.osrstcg.persist.TcgSaveTrigger;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.SidebarRefresh;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.util.Text;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.credit.CreditsRateTracker;

/**
 * {@code ::tcg-give} / {@code ::tcg-complete} / {@code ::tcg-save} / {@code ::tcg-reset} / {@code ::tcg-open}
 * and infobox Open-pack clicks.
 */
@Singleton
public class TcgDebugCommands
{
	private static final Pattern TCG_GIVE_FOIL_SUFFIX = Pattern.compile("(?i)\\s*\\(foil\\)\\s*$");

	private final Client client;
	private final ClientThread clientThread;
	private final ChatMessageManager chatMessageManager;
	private final OsrsTcgConfig config;
	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;
	private final CardCatalogService cardCatalogService;
	private final PackCatalogService packCatalogService;
	private final PackOpenCoordinator packOpenCoordinator;
	private final CloudSessionCoordinator cloudSessionCoordinator;
	private final SidebarRefresh sidebarRefresh;
	private final TcgPartyAnnouncer tcgPartyAnnouncer;
	private final CreditsInfoboxOverlay creditsInfoboxOverlay;
	private final CreditsRateTracker creditsRateTracker;
	private final ConfigManager configManager;

	@Inject
	public TcgDebugCommands(
		Client client,
		ClientThread clientThread,
		ChatMessageManager chatMessageManager,
		OsrsTcgConfig config,
		TcgStateService stateService,
		CardDatabase cardDatabase,
		CardCatalogService cardCatalogService,
		PackCatalogService packCatalogService,
		PackOpenCoordinator packOpenCoordinator,
		CloudSessionCoordinator cloudSessionCoordinator,
		SidebarRefresh sidebarRefresh,
		TcgPartyAnnouncer tcgPartyAnnouncer,
		CreditsInfoboxOverlay creditsInfoboxOverlay,
		CreditsRateTracker creditsRateTracker,
		ConfigManager configManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.chatMessageManager = chatMessageManager;
		this.config = config;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.cardCatalogService = cardCatalogService;
		this.packCatalogService = packCatalogService;
		this.packOpenCoordinator = packOpenCoordinator;
		this.cloudSessionCoordinator = cloudSessionCoordinator;
		this.sidebarRefresh = sidebarRefresh;
		this.tcgPartyAnnouncer = tcgPartyAnnouncer;
		this.creditsInfoboxOverlay = creditsInfoboxOverlay;
		this.creditsRateTracker = creditsRateTracker;
		this.configManager = configManager;
	}

	public void onCommandExecuted(CommandExecuted event)
	{
		if (event == null)
		{
			return;
		}
		String cmd = event.getCommand();
		if (cmd == null || cmd.length() < 4 || !cmd.regionMatches(true, 0, "tcg", 0, 3))
		{
			return;
		}

		if ("tcg-give".equalsIgnoreCase(cmd))
		{
			if (!stateService.isDebugLogging())
			{
				queueGameMessage("[OSRS TCG] That command requires Overview debug mode.");
				return;
			}
			handleGiveCardCommand(event);
			return;
		}

		if ("tcg-complete".equalsIgnoreCase(cmd))
		{
			if (!stateService.isDebugLogging())
			{
				queueGameMessage("[OSRS TCG] That command requires Overview debug mode.");
				return;
			}
			handleCompleteAlbumCommand();
			return;
		}

		if ("tcg-open".equalsIgnoreCase(cmd))
		{
			handleOpenFirstBoosterCommand();
			return;
		}

		if ("tcg-save".equalsIgnoreCase(cmd))
		{
			handleSaveCheckpointCommand();
			return;
		}

		if ("tcg-reset".equalsIgnoreCase(cmd))
		{
			handleResetConfigCommand();
		}
	}

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

	private void handleSaveCheckpointCommand()
	{
		if (stateService.saveFullCheckpoint(TcgSaveTrigger.MANUAL))
		{
			queueGameMessage(String.format(Locale.US,
					"[OSRS TCG] Saved backup. Credits: %s, cards: %s.",
					NumberFormatting.format(stateService.getState().getEconomyState().getCredits()),
					NumberFormatting.format(stateService.getState().getCollectionState().getOwnedInstances().size())));
			return;
		}

		queueGameMessage("[OSRS TCG] Failed to save backup.");
	}

	/**
	 * Unsets every {@code osrstcg} profile + RSProfile config key (tokens, {@code cloudMigrated},
	 * settings, legacy state blobs), restores config defaults, and reconnects with a clean consent gate.
	 */
	private void handleResetConfigCommand()
	{
		final String group = "osrstcg";
		int cleared = 0;

		for (String wholeKey : configManager.getConfigurationKeys(group + "."))
		{
			if (wholeKey == null || wholeKey.length() <= group.length() + 1)
			{
				continue;
			}
			String key = wholeKey.substring(group.length() + 1);
			configManager.unsetConfiguration(group, key);
			cleared++;
		}

		String rsProfile = configManager.getRSProfileKey();
		if (rsProfile != null)
		{
			for (String key : configManager.getRSProfileConfigurationKeys(group, rsProfile, ""))
			{
				if (key == null || key.isEmpty())
				{
					continue;
				}
				configManager.unsetRSProfileConfiguration(group, key);
				cleared++;
			}
		}

		configManager.setDefaultConfiguration(config, true);
		cloudSessionCoordinator.disconnect();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			cloudSessionCoordinator.connect();
		}
		SwingUtilities.invokeLater(sidebarRefresh::refresh);
		queueGameMessage("[OSRS TCG] Cleared " + cleared + " config key(s) and restored defaults.");
	}

	private void handleOpenFirstBoosterCommand()
	{
		List<BoosterPackDefinition> visibleBoosters = packCatalogService.getVisibleBoosters();
		if (visibleBoosters.isEmpty())
		{
			queueGameMessage("[OSRS TCG] No booster packs loaded.");
			return;
		}

		packOpenCoordinator.openFromPlugin(visibleBoosters.get(0), clientThread::invokeLater);
	}

	private void handleCompleteAlbumCommand()
	{
		cardCatalogService.ensureCachedCatalogForDebug();
		Set<String> catalogNames = new LinkedHashSet<>();
		for (CardDefinition card : cardDatabase.getCards())
		{
			if (card == null || card.getName() == null)
			{
				continue;
			}
			String name = card.getName().trim();
			if (!name.isEmpty())
			{
				catalogNames.add(name);
			}
		}

		if (catalogNames.isEmpty())
		{
			queueGameMessage("[OSRS TCG] No cards loaded from the catalog cache yet.");
			return;
		}

		String who = client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null
			? Text.sanitize(client.getLocalPlayer().getName())
			: "";
		String provenance = OwnedCardInstance.withDebugPullMetadataPrefix(who);
		long now = System.currentTimeMillis();

		Map<CardCollectionKey, Integer> ownedBefore = stateService.copyOwnedCardsSnapshot();
		int added = stateService.addOneOfEachCatalogCard(new ArrayList<>(catalogNames), provenance, now);

		if (tcgPartyAnnouncer != null && added > 0)
		{
			Map<CardCollectionKey, Integer> ownedAfter = stateService.getState().getCollectionState().getOwnedCards();
			List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(cardDatabase.getCards());
			for (String category : CollectionSetCompletionUtil.newlyCompletedPrimaryCategories(ownedBefore, ownedAfter, rollPool))
			{
				tcgPartyAnnouncer.announceCollectionSetComplete(category);
			}
		}

		queueGameMessage(String.format(Locale.US, "[OSRS TCG] Added 1× each catalog card (%s cards).",
				NumberFormatting.format(added)));
		sidebarRefresh.refresh();
	}

	private void handleGiveCardCommand(CommandExecuted event)
	{
		String[] arguments = event.getArguments();
		if (arguments == null || arguments.length == 0)
		{
			queueGameMessage("[OSRS TCG] Provide a card name, optionally followed by (foil).");
			return;
		}

		String joined = Arrays.stream(arguments)
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.joining(" "));
		if (joined.isEmpty())
		{
			queueGameMessage("[OSRS TCG] Provide a card name, optionally followed by (foil).");
			return;
		}

		boolean foil = TCG_GIVE_FOIL_SUFFIX.matcher(joined).find();
		String cardQuery = TCG_GIVE_FOIL_SUFFIX.matcher(joined).replaceFirst("").trim();
		if (cardQuery.isEmpty())
		{
			queueGameMessage("[OSRS TCG] Provide a card name, optionally followed by (foil).");
			return;
		}

		cardCatalogService.ensureCachedCatalogForDebug();
		Optional<String> resolved = cardDatabase.getCards().stream()
			.filter(Objects::nonNull)
			.map(CardDefinition::getName)
			.filter(Objects::nonNull)
			.filter(n -> n.trim().equalsIgnoreCase(cardQuery))
			.findFirst()
			.map(n -> n.trim());

		if (!resolved.isPresent())
		{
			queueGameMessage(String.format(Locale.US, "[OSRS TCG] No card named \"%s\" in the catalog.", cardQuery));
			return;
		}

		String canonicalName = resolved.get();
		String who = client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null
			? Text.sanitize(client.getLocalPlayer().getName())
			: "";
		stateService.addCard(canonicalName, foil, 1, OwnedCardInstance.withDebugPullMetadataPrefix(who),
			System.currentTimeMillis());
		queueGameMessage(String.format(Locale.US, "[OSRS TCG] Gave 1× %s%s.", canonicalName, foil ? " (foil)" : ""));
		sidebarRefresh.refresh();
	}

	private void queueGameMessage(String message)
	{
		if (client == null || clientThread == null || message == null || message.isEmpty())
		{
			return;
		}

		clientThread.invokeLater(() ->
			TcgPluginGameMessages.queueGameMessage(chatMessageManager, message));
	}
}

package com.osrstcg;

import com.google.inject.Provides;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.catalog.CardCatalogService;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.session.CloudSessionCoordinator;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.trade.TcgTradeMenuHandler;
import com.osrstcg.cloud.trade.TradeCloudService;
import com.osrstcg.command.TcgDebugCommands;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.state.TcgPublicStats;
import com.osrstcg.overlay.CreditsInfoboxOverlay;
import com.osrstcg.overlay.PackRevealInputListener;
import com.osrstcg.overlay.PackRevealOverlay;
import com.osrstcg.interop.OwnedCardNamesApiService;
import com.osrstcg.credit.CreditAwardService;
import com.osrstcg.credit.CreditsRateTracker;
import com.osrstcg.credit.GameMessageCreditTracker;
import com.osrstcg.credit.NpcKillCreditTracker;
import com.osrstcg.pack.PackSafeModeService;
import com.osrstcg.credit.PlayerCombatMonitor;
import com.osrstcg.party.TcgCollectionSetCompletePartyMessage;
import com.osrstcg.party.TcgPartyInboundHandler;
import com.osrstcg.party.TcgPullPartyMessage;
import com.osrstcg.persist.TcgSaveTrigger;
import com.osrstcg.persist.TcgStateLoadResult;
import com.osrstcg.persist.TcgStateLoadSource;
import com.osrstcg.pack.PackRevealSoundService;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.TcgPanel;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WorldChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "OSRS TCG",
	description = "TCG-style card collecting plugin for Old School RuneScape",
	tags = {"progression", "collection", "community", "card"},
	conflicts = {"Prestige Mode", "Profit Tracker", "Prestige"}
)
public class OsrsTcgPlugin extends Plugin
{
	private static final String TCG_PUBLIC_CHAT_COMMAND = "!tcg";

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ChatMessageManager chatMessageManager;
	@Inject
	private OsrsTcgConfig config;
	@Inject
	private TcgStateService stateService;
	@Inject
	private CardDatabase cardDatabase;
	@Inject
	private CardCatalogService cardCatalogService;
	@Inject
	private ActivityConfigService activityConfigService;
	@Inject
	private PackCatalogService packCatalogService;
	@Inject
	private CreditAwardService creditAwardService;
	@Inject
	private PackRevealService packRevealService;
	@Inject
	private CloudSessionCoordinator cloudSessionCoordinator;
	@Inject
	private TcgDebugCommands tcgDebugCommands;
	@Inject
	private TcgTradeMenuHandler tcgTradeMenuHandler;
	@Inject
	private TcgPartyInboundHandler tcgPartyInboundHandler;
	@Inject
	private PackRevealSoundService packRevealSoundService;
	@Inject
	private PackRevealOverlay packRevealOverlay;
	@Inject
	private CreditsInfoboxOverlay creditsInfoboxOverlay;
	@Inject
	private PackRevealInputListener packRevealInputListener;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private TcgPanel tcgPanel;
	@Inject
	private EventBus eventBus;
	@Inject
	private NpcKillCreditTracker npcKillCreditTracker;
	@Inject
	private GameMessageCreditTracker gameMessageCreditTracker;
	@Inject
	private CreditsRateTracker creditsRateTracker;
	@Inject
	private WSClient wsClient;
	@Inject
	private ChatCommandManager chatCommandManager;
	@Inject
	private ScheduledExecutorService scheduledExecutorService;
	@Inject
	private TcgChatStatsShareService tcgChatStatsShareService;
	@Inject
	private PlayerCombatMonitor playerCombatMonitor;
	@Inject
	private PackSafeModeService packSafeModeService;
	@Inject
	private OwnedCardNamesApiService ownedCardNamesApiService;
	@Inject
	private CloudSessionService cloudSessionService;
	@Inject
	private CloudApiClient cloudApiClient;
	@Inject
	private CreditAttestQueue creditAttestQueue;
	@Inject
	private TradeCloudService tradeCloudService;
	@Inject
	private ConfigManager configManager;

	private NavigationButton navigationButton;
	private long loadedAccountHash = -1L;

	@Override
	protected void startUp()
	{
		// Drop obsolete config keys from older plugin versions.
		configManager.unsetConfiguration("osrstcg", "apiBaseUrl");
		configManager.unsetConfiguration("osrstcg", "webBaseUrl");
		configManager.unsetConfiguration("osrstcg", "groupKey");
		cardCatalogService.loadDiskCacheIfPresent();
		activityConfigService.loadDiskCacheIfPresent();
		// Public catalog/config fetch only after cloud consent (cloudMigrated).
		if (!cloudSessionService.needsCloudConsent())
		{
			cardCatalogService.prefetchAsync();
			activityConfigService.prefetchAsync();
		}
		if (client.getAccountHash() != -1L)
		{
			loadStateForLoggedInAccountIfNeeded();
		}
		log.info("OSRS TCG plugin started. Credits={}, ownedCards={}, cardDefinitions={}",
			NumberFormatting.format(stateService.getState().getEconomyState().getCredits()),
			NumberFormatting.format(stateService.getState().getCollectionState().getOwnedCards().size()),
			NumberFormatting.format(cardDatabase.size()));
		if (cardDatabase.size() > 0)
		{
			log.info("Card category distribution: {}", cardDatabase.categoryCounts());
		}
		else
		{
			log.info("Card catalog empty until fetched from API (/api/v1/catalog/cards/live)");
		}
		navigationButton = NavigationButton.builder()
			.tooltip("OSRS TCG")
			.icon(buildPanelIcon())
			.priority(5)
			.panel(tcgPanel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		overlayManager.add(packRevealOverlay);
		overlayManager.add(creditsInfoboxOverlay);
		mouseManager.registerMouseListener(packRevealInputListener);
		mouseManager.registerMouseWheelListener(packRevealInputListener);
		keyManager.registerKeyListener(packRevealInputListener);
		eventBus.register(creditAwardService);
		creditAwardService.onPluginStarted();
		eventBus.register(creditsRateTracker);
		cloudSessionService.registerAccountLockCleanup(creditAwardService::stopCreditTrackingForAccountLock);
		cloudSessionService.registerAccountLockCleanup(npcKillCreditTracker::shutdown);
		eventBus.register(npcKillCreditTracker);
		eventBus.register(gameMessageCreditTracker);
		eventBus.register(playerCombatMonitor);
		eventBus.register(packSafeModeService);
		wsClient.registerMessage(TcgPullPartyMessage.class);
		wsClient.registerMessage(TcgCollectionSetCompletePartyMessage.class);
		chatCommandManager.registerCommandAsync(
			TCG_PUBLIC_CHAT_COMMAND, this::lookupTcgPublicStatsChatCommand);
		tcgPanel.start();
		cloudSessionCoordinator.installStatusListener();
		tradeCloudService.setInboxListener(tcgPanel::refresh);
		creditAttestQueue.setEconomyListener(tcgPanel::refreshCredits);
		packCatalogService.setChangeListener(() -> javax.swing.SwingUtilities.invokeLater(tcgPanel::refresh));
		cardCatalogService.setChangeListener(() -> javax.swing.SwingUtilities.invokeLater(tcgPanel::refresh));
		ownedCardNamesApiService.start();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			cloudSessionCoordinator.connect();
		}
		tcgPanel.refresh();
		TcgPluginGameMessages.setPrefixColor(config.chatPrefixColor());
	}

	@Override
	protected void shutDown()
	{
		// Persist before any blocking cloud I/O - same order as logout / ClientShutdown.
		// Otherwise a hung attest flush can skip the checkpoint and the next startUp loads stale disk.
		creditAwardService.flushSkillBaselineForPersist();
		if (!stateService.saveFullCheckpoint(TcgSaveTrigger.PLUGIN_UNLOAD))
		{
			log.warn("OSRS TCG failed to write local checkpoint on plugin unload");
		}
		try
		{
			cloudSessionCoordinator.disconnect();
		}
		catch (Exception ex)
		{
			log.warn("Cloud disconnect on plugin unload failed", ex);
		}

		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}
		eventBus.unregister(creditAwardService);
		eventBus.unregister(creditsRateTracker);
		eventBus.unregister(npcKillCreditTracker);
		eventBus.unregister(gameMessageCreditTracker);
		eventBus.unregister(playerCombatMonitor);
		eventBus.unregister(packSafeModeService);
		playerCombatMonitor.reset();
		wsClient.unregisterMessage(TcgPullPartyMessage.class);
		wsClient.unregisterMessage(TcgCollectionSetCompletePartyMessage.class);
		chatCommandManager.unregisterCommand(TCG_PUBLIC_CHAT_COMMAND);
		npcKillCreditTracker.shutdown();
		overlayManager.remove(packRevealOverlay);
		overlayManager.remove(creditsInfoboxOverlay);
		mouseManager.unregisterMouseListener(packRevealInputListener);
		mouseManager.unregisterMouseWheelListener(packRevealInputListener);
		keyManager.unregisterKeyListener(packRevealInputListener);
		packRevealSoundService.hardStop();
		packRevealService.reset();
		cloudSessionCoordinator.clearStatusListener();
		tradeCloudService.setInboxListener(null);
		creditAttestQueue.setEconomyListener(null);
		packCatalogService.setChangeListener(null);
		ownedCardNamesApiService.stop();
		tcgPanel.stop();
		log.info("OSRS TCG plugin stopped");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		handlePendingPackOpenTimeout();
		cloudSessionCoordinator.onLoggedInGameTick();
	}

	/** Closes the pack overlay if the cloud open RPC stalls past {@link PackRevealService#PENDING_PULLS_TIMEOUT_MS}. */
	private void handlePendingPackOpenTimeout()
	{
		if (!packRevealService.isAwaitingServerPulls())
		{
			return;
		}
		packRevealService.tick();
		if (!packRevealService.consumePendingPullsTimeout())
		{
			return;
		}
		queueGameMessage("[OSRS TCG] " + PackRevealService.PENDING_PULLS_TIMEOUT_MESSAGE);
		tcgPanel.clearPackRevealSidebarFreeze();
		tcgPanel.refreshAfterPackRevealClose();
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// RSProfile / local checkpoint must stay synchronous so ConfigManager's ClientShutdown
		// handler (priority -100) can sendConfig() with the latest keys.
		creditAwardService.flushSkillBaselineForPersist();
		stateService.saveFullCheckpoint(TcgSaveTrigger.CLIENT_SHUTDOWN);

		// Network attest flush is registered as a Future so ClientUI.waitForAllConsumers
		// (≈10s) keeps the process alive until the HTTP drain finishes or times out.
		event.waitFor(CompletableFuture.runAsync(cloudSessionCoordinator::flushAttestsForShutdown, scheduledExecutorService));
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (creditAwardService.onStatChanged(event))
		{
			tcgPanel.refreshCredits();
		}
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (creditAwardService.onFakeXpDrop(event))
		{
			tcgPanel.refreshCredits();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		creditAwardService.onGameStateChanged(event);
		GameState gs = event.getGameState();
		queueDebugMessage(String.format("GameStateChanged: %s",
			gs == null ? "null" : gs.name()));

		if (gs == GameState.LOGIN_SCREEN)
		{
			stateService.saveFullCheckpoint(TcgSaveTrigger.LOGOUT);
			loadedAccountHash = -1L;
		}
		else if (gs == GameState.LOGGED_IN)
		{
			loadStateForLoggedInAccountIfNeeded();
		}
		cloudSessionCoordinator.onGameStateChanged(event);
		tcgPanel.refresh();
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		queueDebugMessage(String.format("WorldChanged: world=%d types=%s",
			client.getWorld(),
			client.getWorldType() == null ? "[]" : client.getWorldType().toString()));
		cloudSessionCoordinator.onWorldChanged(event);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event == null || !"osrstcg".equals(event.getGroup()))
		{
			return;
		}
		if ("chatPrefixColor".equals(event.getKey()))
		{
			TcgPluginGameMessages.setPrefixColor(config.chatPrefixColor());
		}
		if ("compactShop".equals(event.getKey()))
		{
			javax.swing.SwingUtilities.invokeLater(tcgPanel::refresh);
		}
	}

	@Subscribe
	public void onTcgPullPartyMessage(TcgPullPartyMessage message)
	{
		tcgPartyInboundHandler.onPull(message);
	}

	@Subscribe
	public void onTcgCollectionSetCompletePartyMessage(TcgCollectionSetCompletePartyMessage message)
	{
		tcgPartyInboundHandler.onCollectionSetComplete(message);
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		cloudSessionCoordinator.connect();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		tcgTradeMenuHandler.onMenuEntryAdded(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		tcgTradeMenuHandler.onMenuOptionClicked(event);
	}

	/** After {@link TcgStateService#load()} on login / account switch; clears UI when debug-tainted saves are reset. */
	private void applyLoadedProfileState(TcgStateLoadResult loadResult)
	{
		creditAwardService.resetExperienceCreditBaseline();
		if (loadResult != null && loadResult.isDebugResetOnLoad())
		{
			packRevealService.reset();
			tcgPanel.clearPackRevealSidebarFreeze();
			tcgPanel.resetSessionUi();
			queueGameMessage(
				"[OSRS TCG] This profile was saved with debug mode on; collection and credits were reset.");
		}
		else
		{
			tcgPanel.refresh();
		}
	}

	private void announceLoadResult(TcgStateLoadResult loadResult)
	{
		if (loadResult == null)
		{
			return;
		}

		if (loadResult.getSource() == TcgStateLoadSource.DISK)
		{
			queueDebugMessage("Restored progress from tcg.save.");
		}
		else if (loadResult.getSource() == TcgStateLoadSource.DISK_SNAPSHOT)
		{
			queueGameMessage("[OSRS TCG] Restored progress from a disk snapshot.");
		}
	}

	private void loadStateForLoggedInAccountIfNeeded()
	{
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			return;
		}
		if (loadedAccountHash == accountHash)
		{
			return;
		}
		TcgStateLoadResult loadResult = stateService.load();
		applyLoadedProfileState(loadResult);
		announceLoadResult(loadResult);
		loadedAccountHash = accountHash;
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

	private void queueDebugMessage(String message)
	{
		if (client == null || clientThread == null || message == null || message.isEmpty())
		{
			return;
		}
		if (config == null || !config.debugMessages())
		{
			return;
		}

		clientThread.invokeLater(() ->
			TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, message));
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		tcgDebugCommands.onCommandExecuted(event);
	}

	@Subscribe
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		tcgDebugCommands.onOverlayMenuClicked(event);
	}

	private void lookupTcgPublicStatsChatCommand(ChatMessage chatMessage, String message)
	{
		if (!message.trim().equalsIgnoreCase(TCG_PUBLIC_CHAT_COMMAND))
		{
			return;
		}

		final String player;
		if (ChatMessageType.PRIVATECHATOUT.equals(chatMessage.getType()))
		{
			if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
			{
				return;
			}
			player = Text.sanitize(client.getLocalPlayer().getName());
		}
		else
		{
			player = Text.sanitize(chatMessage.getName());
		}

		MessageNode messageNode = chatMessage.getMessageNode();
		if (messageNode == null)
		{
			return;
		}

		TcgPublicStats cached = tcgChatStatsShareService.getBySanitizedPlayerName(player);
		if (cached != null)
		{
			messageNode.setRuneLiteFormatMessage(tcgChatStatsShareService.buildColoredLine(cached));
			client.refreshChat();
			return;
		}

		scheduledExecutorService.execute(() ->
		{
			try
			{
				TcgPublicStats stats = TcgPublicStats.fromPlayerStatsJson(cloudApiClient.getPublicPlayerStats(player));
				if (stats == null)
				{
					return;
				}
				tcgChatStatsShareService.putSanitizedPlayerName(player, stats);
				clientThread.invokeLater(() ->
				{
					messageNode.setRuneLiteFormatMessage(tcgChatStatsShareService.buildColoredLine(stats));
					client.refreshChat();
				});
			}
			catch (CloudApiException ex)
			{
				// 404 player_not_found (private/sandbox/missing) - leave plain !tcg
				log.debug("!tcg cloud lookup for {}: {} {}", player, ex.getCode(), ex.getMessage());
			}
			catch (Exception ex)
			{
				log.debug("!tcg cloud lookup failed for {}", player, ex);
			}
		});
	}

	private BufferedImage buildPanelIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0x2B2B2B));
		g.fillRect(0, 0, 16, 16);
		g.setColor(new Color(0xF2C94C));
		g.fillRoundRect(2, 2, 12, 12, 3, 3);
		g.setColor(Color.BLACK);
		g.drawString("T", 5, 12);
		g.dispose();
		return image;
	}

	@Provides
	OsrsTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsTcgConfig.class);
	}
}

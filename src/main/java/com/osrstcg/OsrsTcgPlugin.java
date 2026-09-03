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
import com.osrstcg.command.TcgResetCommand;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.state.TcgPublicStats;
import com.osrstcg.overlay.CreditsInfoboxMenuHandler;
import com.osrstcg.overlay.CreditsInfoboxOverlay;
import com.osrstcg.overlay.PackRevealInputListener;
import com.osrstcg.overlay.PackRevealOverlay;
import com.osrstcg.interop.OwnedCardNamesApiService;
import com.osrstcg.credit.CreditAwardService;
import com.osrstcg.credit.CreditsRateTracker;
import com.osrstcg.credit.GameMessageCreditTracker;
import com.osrstcg.credit.NpcKillCreditTracker;
import com.osrstcg.pack.PackRevealService;
import com.osrstcg.party.TcgCollectionSetCompletePartyMessage;
import com.osrstcg.party.TcgPartyInboundHandler;
import com.osrstcg.party.TcgPullPartyMessage;
import com.osrstcg.persist.TcgSaveTrigger;
import com.osrstcg.persist.TcgStateLoadResult;
import com.osrstcg.pack.PackRevealSoundService;
import com.osrstcg.interop.TcgChatStatsShareService;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.TcgPanel;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
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
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/**
 * RuneLite plugin entry point for OSRS TCG. Wires up the sidebar panel, pack-opening overlays,
 * credit-earning trackers, and cloud sync services via Guice injection, and forwards RuneLite
 * events to the relevant subsystem. Most of the actual logic lives in the injected services;
 * this class is mainly lifecycle wiring ({@link #startUp()}/{@link #shutDown()}) and event
 * dispatch.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS TCG",
	description = "TCG-style card collecting plugin for Old School RuneScape",
	tags = {"progression", "collection", "community", "card"},
	conflicts = {"Prestige Mode", "Profit Tracker", "Prestige"}
)
public class OsrsTcgPlugin extends Plugin
{
	/** Public chat command players can send/receive to look up another player's TCG stats. */
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
	private TcgResetCommand tcgResetCommand;
	@Inject
	private CreditsInfoboxMenuHandler creditsInfoboxMenuHandler;
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
	/** Account hash whose state was last loaded via {@link #loadStateIfLoggedIn()}, or -1 if none yet. */
	private long loadedAccountHash = -1L;

	/**
	 * Registers the sidebar panel, overlays, input listeners, and event subscriptions, and kicks
	 * off catalog prefetch and (if already logged in) state loading and cloud connect.
	 */
	@Override
	protected void startUp()
	{
		configManager.unsetConfiguration("osrstcg", "apiBaseUrl");
		configManager.unsetConfiguration("osrstcg", "webBaseUrl");
		configManager.unsetConfiguration("osrstcg", "groupKey");
		cardCatalogService.loadDiskCacheIfPresent();
		activityConfigService.loadDiskCacheIfPresent();
		if (!cloudSessionService.needsCloudConsent())
		{
			cardCatalogService.prefetchAsync();
			activityConfigService.prefetchAsync();
		}
		if (client.getAccountHash() != -1L)
		{
			loadStateIfLoggedIn();
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
		cloudSessionService.registerAccountLockCleanup(creditAwardService::stopCreditTrackingOnLock);
		cloudSessionService.registerAccountLockCleanup(npcKillCreditTracker::shutdown);
		eventBus.register(npcKillCreditTracker);
		eventBus.register(gameMessageCreditTracker);
		wsClient.registerMessage(TcgPullPartyMessage.class);
		wsClient.registerMessage(TcgCollectionSetCompletePartyMessage.class);
		chatCommandManager.registerCommandAsync(
			TCG_PUBLIC_CHAT_COMMAND, this::lookupPublicStatsChatCommand);
		tcgPanel.start();
		cloudSessionCoordinator.installStatusListener();
		tradeCloudService.setInboxListener(tcgPanel::refresh);
		creditAttestQueue.setEconomyListener(tcgPanel::refreshCredits);
		packCatalogService.setChangeListener(this::refreshPanelOnEdt);
		cardCatalogService.setChangeListener(this::refreshPanelOnEdt);
		ownedCardNamesApiService.start();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			cloudSessionCoordinator.connect();
		}
		tcgPanel.refresh();
		TcgPluginGameMessages.setPrefixColor(config.chatPrefixColor());
	}

	/**
	 * Flushes pending credit/state writes, disconnects from the cloud session, and tears down
	 * everything registered in {@link #startUp()}.
	 */
	@Override
	protected void shutDown()
	{
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

	/** Times out an in-flight pack reveal still waiting on server pull results, closing the sidebar freeze if it does. */
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
		if (client != null)
		{
			TcgPluginGameMessages.queueOnClientThread(clientThread, chatMessageManager,
				PackRevealService.PENDING_PULLS_TIMEOUT_MESSAGE);
		}
		tcgPanel.clearPackRevealSidebarFreeze();
		tcgPanel.refreshAfterPackRevealClose();
	}

	/** Saves a final checkpoint and blocks client shutdown until queued attests are flushed to the cloud. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		creditAwardService.flushSkillBaselineForPersist();
		stateService.saveFullCheckpoint(TcgSaveTrigger.CLIENT_SHUTDOWN);
		event.waitFor(CompletableFuture.runAsync(cloudSessionCoordinator::flushAttestsForShutdown, scheduledExecutorService));
	}

	/** Awards XP-based credits for the stat gain, refreshing the sidebar if any credits were granted. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		refreshCreditsIf(creditAwardService.onStatChanged(event));
	}

	/** Awards credits for a fake (non-tracked-skill) XP drop, refreshing the sidebar if any were granted. */
	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		refreshCreditsIf(creditAwardService.onFakeXpDrop(event));
	}

	/** On login, loads the account's saved state; on logout, checkpoints and clears the loaded-account marker. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		creditAwardService.onGameStateChanged(event);
		GameState gs = event.getGameState();

		if (gs == GameState.LOGIN_SCREEN)
		{
			stateService.saveFullCheckpoint(TcgSaveTrigger.LOGOUT);
			loadedAccountHash = -1L;
		}
		else if (gs == GameState.LOGGED_IN)
		{
			loadStateIfLoggedIn();
		}
		cloudSessionCoordinator.onGameStateChanged(event);
		tcgPanel.refresh();
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		cloudSessionCoordinator.onWorldChanged(event);
	}

	/** Reacts to plugin config changes: chat prefix color and compact-shop layout take effect live. */
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
			refreshPanelOnEdt();
		}
	}

	/** Handles an incoming party pull announcement from another party member. */
	@Subscribe
	public void onTcgPullPartyMessage(TcgPullPartyMessage message)
	{
		tcgPartyInboundHandler.onPull(message);
	}

	/** Handles an incoming party announcement that a member completed a collection set. */
	@Subscribe
	public void onTcgCollectionSetCompletePartyMessage(TcgCollectionSetCompletePartyMessage message)
	{
		tcgPartyInboundHandler.onCollectionSetComplete(message);
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		loadedAccountHash = -1L;
		loadStateIfLoggedIn();
		cloudSessionCoordinator.connect();
	}

	/** Adds the TCG trade menu entry to eligible right-click menus. */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		tcgTradeMenuHandler.onMenuEntryAdded(event);
	}

	/** Handles clicks on the TCG trade menu entry. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		tcgTradeMenuHandler.onMenuOptionClicked(event);
	}

	/** Resets the XP credit baseline and refreshes the sidebar after a profile's state finishes loading. */
	private void applyLoadedProfileState(TcgStateLoadResult loadResult)
	{
		creditAwardService.resetExperienceCreditBaseline();
		tcgPanel.refresh();
	}

	/** Loads saved state for the current account once per login, no-op if already loaded or logged out. */
	private void loadStateIfLoggedIn()
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
		loadedAccountHash = accountHash;
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		tcgResetCommand.onCommandExecuted(event);
	}

	@Subscribe
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		creditsInfoboxMenuHandler.onOverlayMenuClicked(event);
	}

	/**
	 * Handles the {@code !tcg} public/private chat command: serves a cached stats line if we have
	 * one for the requesting player, otherwise fetches from the cloud API asynchronously and
	 * patches the chat message in once the result arrives.
	 */
	private void lookupPublicStatsChatCommand(ChatMessage chatMessage, String message)
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
				log.debug("!tcg cloud lookup for {}: {} {}", player, ex.getCode(), ex.getMessage());
			}
			catch (Exception ex)
			{
				log.debug("!tcg cloud lookup failed for {}", player, ex);
			}
		});
	}

	/** Refreshes the sidebar's credits display, but only if the caller reports credits were awarded. */
	private void refreshCreditsIf(boolean awarded)
	{
		if (awarded)
		{
			tcgPanel.refreshCredits();
		}
	}

	/** Schedules a full sidebar refresh on the Swing EDT. */
	private void refreshPanelOnEdt()
	{
		SwingUtilities.invokeLater(tcgPanel::refresh);
	}

	/** Loads the sidebar navigation button icon from plugin resources. */
	private BufferedImage buildPanelIcon()
	{
		return ImageUtil.loadImageResource(OsrsTcgPlugin.class, "/com/osrstcg/images/sidebar.png");
	}

	/** Guice provider wiring the RuneLite-generated config proxy for {@link OsrsTcgConfig}. */
	@Provides
	OsrsTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsTcgConfig.class);
	}
}

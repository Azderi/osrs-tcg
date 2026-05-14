package com.runelitetcg;

import com.google.inject.Provides;
import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.PackCatalog;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.TcgPublicStats;
import com.runelitetcg.overlay.PackRevealInputListener;
import com.runelitetcg.overlay.PackRevealOverlay;
import com.runelitetcg.service.CardPartyTransferService;
import com.runelitetcg.service.CreditAwardService;
import com.runelitetcg.service.NpcKillCreditTracker;
import com.runelitetcg.service.PackOpeningService;
import com.runelitetcg.party.TcgCardGiftPartyMessage;
import com.runelitetcg.party.TcgCardGiftResponsePartyMessage;
import com.runelitetcg.party.TcgChatStatsPartyMessage;
import com.runelitetcg.party.TcgCollectionSetCompletePartyMessage;
import com.runelitetcg.party.TcgPullPartyMessage;
import com.runelitetcg.service.PackRevealSoundService;
import com.runelitetcg.service.PackRevealService;
import com.runelitetcg.service.TcgChatStatsShareService;
import com.runelitetcg.service.TcgPartyAnnouncer;
import com.runelitetcg.service.TcgPublicStatsCalculator;
import com.runelitetcg.service.TcgStateService;
import com.runelitetcg.ui.TcgPanel;
import com.runelitetcg.ui.collectionalbum.CollectionAlbumManager;
import com.runelitetcg.util.NumberFormatting;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ChatInput;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
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
	description = "TCG-style collection plugin powered by Card.json"
)
public class RuneLiteTcgPlugin extends Plugin
{
	private static final String TCG_PUBLIC_CHAT_COMMAND = "!tcg";

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private RuneLiteTcgConfig config;
	@Inject
	private TcgStateService stateService;
	@Inject
	private CardDatabase cardDatabase;
	@Inject
	private PackCatalog packCatalog;
	@Inject
	private CreditAwardService creditAwardService;
	@Inject
	private PackOpeningService packOpeningService;
	@Inject
	private PackRevealService packRevealService;
	@Inject
	private PackRevealSoundService packRevealSoundService;
	@Inject
	private PackRevealOverlay packRevealOverlay;
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
	private CollectionAlbumManager collectionAlbumManager;
	@Inject
	private EventBus eventBus;
	@Inject
	private NpcKillCreditTracker npcKillCreditTracker;
	@Inject
	private PartyService partyService;
	@Inject
	private WSClient wsClient;
	@Inject
	private CardPartyTransferService cardPartyTransferService;
	@Inject
	private ChatCommandManager chatCommandManager;
	@Inject
	private ScheduledExecutorService scheduledExecutorService;
	@Inject
	private TcgPublicStatsCalculator tcgPublicStatsCalculator;
	@Inject
	private TcgChatStatsShareService tcgChatStatsShareService;
	@Inject
	private TcgPartyAnnouncer tcgPartyAnnouncer;

	private NavigationButton navigationButton;

	@Override
	protected void startUp()
	{
		cardDatabase.load();
		packCatalog.load();
		stateService.load();
		creditAwardService.resetExperienceCreditBaseline();
		log.info("OSRS TCG plugin started. Credits={}, ownedCards={}, cardDefinitions={}",
			NumberFormatting.format(stateService.getState().getEconomyState().getCredits()),
			NumberFormatting.format(stateService.getState().getCollectionState().getOwnedCards().size()),
			NumberFormatting.format(cardDatabase.size()));
		log.info("Card category distribution: {}", cardDatabase.categoryCounts());
		navigationButton = NavigationButton.builder()
			.tooltip("OSRS TCG")
			.icon(buildPanelIcon())
			.priority(5)
			.panel(tcgPanel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		overlayManager.add(packRevealOverlay);
		// Append (no index): Stretched Mode registers TranslateMouseListener at 0 — if we also used 0, whichever
		// plugin starts last runs first and can receive unstretched coordinates while the overlay uses game canvas space.
		// See runelite stretchedmode TranslateMouseListener / StretchedModePlugin.
		mouseManager.registerMouseListener(packRevealInputListener);
		mouseManager.registerMouseWheelListener(packRevealInputListener);
		keyManager.registerKeyListener(packRevealInputListener);
		eventBus.register(npcKillCreditTracker);
		eventBus.register(cardPartyTransferService);
		wsClient.registerMessage(TcgPullPartyMessage.class);
		wsClient.registerMessage(TcgCollectionSetCompletePartyMessage.class);
		wsClient.registerMessage(TcgCardGiftPartyMessage.class);
		wsClient.registerMessage(TcgCardGiftResponsePartyMessage.class);
		wsClient.registerMessage(TcgChatStatsPartyMessage.class);
		chatCommandManager.registerCommandAsync(
			TCG_PUBLIC_CHAT_COMMAND, this::lookupTcgPublicStatsChatCommand, this::submitTcgPublicStatsChatCommand);
		tcgPanel.start();
		stateService.setRewardTuningFlushBeforeCredits(tcgPanel::flushRewardTuningDraftToState);
		tcgPanel.refresh();
	}

	@Override
	protected void shutDown()
	{
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}
		eventBus.unregister(npcKillCreditTracker);
		eventBus.unregister(cardPartyTransferService);
		wsClient.unregisterMessage(TcgPullPartyMessage.class);
		wsClient.unregisterMessage(TcgCollectionSetCompletePartyMessage.class);
		wsClient.unregisterMessage(TcgCardGiftPartyMessage.class);
		wsClient.unregisterMessage(TcgCardGiftResponsePartyMessage.class);
		wsClient.unregisterMessage(TcgChatStatsPartyMessage.class);
		chatCommandManager.unregisterCommand(TCG_PUBLIC_CHAT_COMMAND);
		npcKillCreditTracker.shutdown();
		overlayManager.remove(packRevealOverlay);
		mouseManager.unregisterMouseListener(packRevealInputListener);
		mouseManager.unregisterMouseWheelListener(packRevealInputListener);
		keyManager.unregisterKeyListener(packRevealInputListener);
		packRevealSoundService.hardStop();
		packRevealService.reset();
		collectionAlbumManager.dispose();
		stateService.setRewardTuningFlushBeforeCredits(null);
		tcgPanel.stop();
		stateService.save();
		log.info("OSRS TCG plugin stopped");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (creditAwardService.onGameTick(event))
		{
			tcgPanel.refresh();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		creditAwardService.onStatChanged(event);
		tcgPanel.refresh();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		creditAwardService.onGameStateChanged(event);
		GameState gs = event.getGameState();
		if (gs == GameState.LOGIN_SCREEN || gs == GameState.HOPPING)
		{
			stateService.save();
		}
		tcgPanel.refresh();
	}

	@Subscribe
	public void onTcgPullPartyMessage(TcgPullPartyMessage message)
	{
		if (!config.partyAnnounceMythicPulls())
		{
			return;
		}
		if (message == null)
		{
			return;
		}
		String cardName = message.getCardName();
		if (cardName == null || cardName.trim().isEmpty())
		{
			return;
		}
		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && message.getMemberId() == localMember.getMemberId())
		{
			return;
		}
		PartyMember author = partyService.getMemberById(message.getMemberId());
		String who = author != null && author.getDisplayName() != null && !author.getDisplayName().trim().isEmpty()
			? author.getDisplayName().trim()
			: "A party member";
		String trimmedCard = cardName.trim();
		String line = message.isNewForCollection()
			? String.format("[OSRS TCG] %s just added '%s' to their collection!", who, trimmedCard)
			: String.format("[OSRS TCG] %s just pulled %s!", who, trimmedCard);
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", line, null));
	}

	@Subscribe
	public void onTcgCollectionSetCompletePartyMessage(TcgCollectionSetCompletePartyMessage message)
	{
		if (!config.partyAnnounceMythicPulls())
		{
			return;
		}
		if (message == null)
		{
			return;
		}
		String collectionName = message.getCollectionName();
		if (collectionName == null || collectionName.trim().isEmpty())
		{
			return;
		}
		PartyMember localMember = partyService.getLocalMember();
		if (localMember != null && message.getMemberId() == localMember.getMemberId())
		{
			return;
		}
		PartyMember author = partyService.getMemberById(message.getMemberId());
		String who = author != null && author.getDisplayName() != null && !author.getDisplayName().trim().isEmpty()
			? author.getDisplayName().trim()
			: "A party member";
		String line = String.format("[OSRS TCG] %s just finished '%s'!", who, collectionName.trim());
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", line, null));
	}

	@Subscribe
	public void onTcgChatStatsPartyMessage(TcgChatStatsPartyMessage message)
	{
		if (message == null)
		{
			return;
		}
		tcgChatStatsShareService.ingestPartyMessage(message, partyService);
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		stateService.load();
		creditAwardService.resetExperienceCreditBaseline();
		tcgPanel.syncRewardDraftFromPersistent();
		tcgPanel.refresh();
		if (stateService.isDebugLogging())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				String.format("[OSRS TCG] Profile state loaded. Credits: %s", NumberFormatting.format(stateService.getCredits())),
				null);
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if ("tcg-set".equalsIgnoreCase(event.getCommand()))
		{
			if (!stateService.isDebugLogging())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[OSRS TCG] ::tcg-set requires debug mode (Overview tab: enable before reward multipliers lock).",
					null);
				return;
			}
			handleSetCreditsCommand(event);
			return;
		}

		if ("tcg-reset".equalsIgnoreCase(event.getCommand()))
		{
			handleResetCommand();
			return;
		}

		if (!"tcg-open".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}

		if (packCatalog.getBoosters().isEmpty())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSRS TCG] No booster packs loaded.", null);
			return;
		}

		tcgPanel.beginPackRevealSidebarFreeze();
		HashSet<CardCollectionKey> preOwned = new HashSet<>(stateService.getState().getCollectionState().getOwnedCards().keySet());
		boolean showScrollWheelHint = stateService.getState().getEconomyState().getOpenedPacks() == 0L;
		var result = packOpeningService.buyAndOpenPack(packCatalog.getBoosters().get(0));
		if (!result.isSuccess())
		{
			tcgPanel.clearPackRevealSidebarFreeze();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSRS TCG] " + result.getMessage(), null);
			tcgPanel.refresh();
			return;
		}

		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			String.format("[OSRS TCG] Opened pack for %s credits. New balance: %s. Pulled %s cards.",
				NumberFormatting.format(result.getPackPrice()), NumberFormatting.format(result.getCreditsAfter()),
				NumberFormatting.format(result.getPulls().size())),
			null);
		packRevealService.startReveal(result.getPulls(), preOwned, result.getBoosterDisplayName(),
			result.getBoosterPackId(), showScrollWheelHint);
		tcgPanel.refresh();
	}

	private void handleSetCreditsCommand(CommandExecuted event)
	{
		String[] arguments = event.getArguments();
		if (arguments == null || arguments.length < 1)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Usage: ::tcg-set <credits>", null);
			return;
		}

		String amountRaw = String.join("", Arrays.asList(arguments)).trim();
		long amount;
		try
		{
			amount = Long.parseLong(amountRaw);
		}
		catch (NumberFormatException ex)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Invalid credit amount. Usage: ::tcg-set <credits>", null);
			return;
		}

		if (amount < 0)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Credits cannot be negative.", null);
			return;
		}

		long currentCredits = stateService.getCredits();
		if (amount > currentCredits)
		{
			stateService.addCredits(amount - currentCredits);
		}
		else if (amount < currentCredits)
		{
			stateService.spendCredits(currentCredits - amount);
		}

		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			String.format("[OSRS TCG] Credits set to %s.", NumberFormatting.format(stateService.getCredits())), null);
		tcgPanel.refresh();
	}

	private void handleResetCommand()
	{
		tcgPanel.performCollectionReset();
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

		TcgPublicStats stats = tcgChatStatsShareService.getBySanitizedPlayerName(player);
		if (stats == null)
		{
			return;
		}

		String response = tcgChatStatsShareService.buildColoredLine(stats);
		MessageNode messageNode = chatMessage.getMessageNode();
		if (messageNode == null)
		{
			return;
		}
		messageNode.setRuneLiteFormatMessage(response);
		client.refreshChat();
	}

	private boolean submitTcgPublicStatsChatCommand(ChatInput chatInput, String value)
	{
		if (!value.trim().equalsIgnoreCase(TCG_PUBLIC_CHAT_COMMAND))
		{
			return false;
		}
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return false;
		}

		TcgPublicStats stats = tcgPublicStatsCalculator.computeLive();
		tcgChatStatsShareService.putSanitizedPlayerName(Text.sanitize(client.getLocalPlayer().getName()), stats);

		scheduledExecutorService.execute(() ->
		{
			try
			{
				tcgPartyAnnouncer.broadcastChatCommandStats(stats);
			}
			catch (Exception ex)
			{
				log.debug("!tcg party broadcast failed", ex);
			}
			finally
			{
				chatInput.resume();
			}
		});
		return true;
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
	RuneLiteTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneLiteTcgConfig.class);
	}
}

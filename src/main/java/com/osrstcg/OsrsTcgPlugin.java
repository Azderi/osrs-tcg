package com.osrstcg;

import com.google.inject.Provides;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.data.PackCatalog;
import com.osrstcg.model.CardCollectionKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.TcgPublicStats;
import com.osrstcg.overlay.PackRevealInputListener;
import com.osrstcg.overlay.PackRevealOverlay;
import com.osrstcg.service.CardPartyTransferService;
import com.osrstcg.service.CreditAwardService;
import com.osrstcg.service.NpcKillCreditTracker;
import com.osrstcg.service.CollectionSetCompletionUtil;
import com.osrstcg.service.PackOpeningService;
import com.osrstcg.service.PackSafeModeService;
import com.osrstcg.service.PlayerCombatMonitor;
import com.osrstcg.service.RollPoolFilter;
import com.osrstcg.party.TcgCardGiftPartyMessage;
import com.osrstcg.party.TcgCardGiftResponsePartyMessage;
import com.osrstcg.party.TcgChatStatsPartyMessage;
import com.osrstcg.party.TcgCollectionSetCompletePartyMessage;
import com.osrstcg.party.TcgPullPartyMessage;
import com.osrstcg.service.PackRevealSoundService;
import com.osrstcg.service.PackRevealService;
import com.osrstcg.service.TcgChatStatsShareService;
import com.osrstcg.service.TcgPartyAnnouncer;
import com.osrstcg.service.TcgPublicStatsCalculator;
import com.osrstcg.service.TcgStateService;
import com.osrstcg.ui.TcgPanel;
import com.osrstcg.ui.collectionalbum.CollectionAlbumManager;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
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
	description = "TCG-style card collecting plugin for Old School RuneScape"
)
public class OsrsTcgPlugin extends Plugin
{
	private static final String TCG_PUBLIC_CHAT_COMMAND = "!tcg";
	private static final Pattern TCG_GIVE_FOIL_SUFFIX = Pattern.compile("(?i)\\s*\\(foil\\)\\s*$");

	@Inject
	private Client client;
	@Inject
	private ChatMessageManager chatMessageManager;
	@Inject
	private OsrsTcgConfig config;
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
	@Inject
	private PlayerCombatMonitor playerCombatMonitor;
	@Inject
	private PackSafeModeService packSafeModeService;

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
		eventBus.register(playerCombatMonitor);
		eventBus.register(packSafeModeService);
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
		eventBus.unregister(playerCombatMonitor);
		eventBus.unregister(packSafeModeService);
		playerCombatMonitor.reset();
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
		String trimmed = cardName.trim();
		Color rarity = cardDatabase.chatRarityColorForCardName(trimmed);
		String formatted = message.isNewForCollection()
			? TcgPluginGameMessages.formatGoldPrefixedSomeoneAddedCollection(who, trimmed, message.isFoil(), rarity)
			: TcgPluginGameMessages.formatGoldPrefixedSomeonePulled(who, trimmed, message.isFoil(), rarity);
		String plain = message.isNewForCollection()
			? TcgPluginGameMessages.plainGoldPrefixedSomeoneAddedCollection(who, trimmed, message.isFoil())
			: TcgPluginGameMessages.plainGoldPrefixedSomeonePulled(who, trimmed, message.isFoil());
		TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
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
		TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager,
			String.format(Locale.US, "%s just finished %s!", who, collectionName.trim()));
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
	}

	@Subscribe
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

		if ("tcg-set".equalsIgnoreCase(cmd))
		{
			if (!stateService.isDebugLogging())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[OSRS TCG] ::tcg-set requires debug mode",
					null);
				return;
			}
			handleSetCreditsCommand(event);
			return;
		}

		if ("tcg-reset".equalsIgnoreCase(cmd))
		{
			handleResetCommand();
			return;
		}

		if ("tcg-give".equalsIgnoreCase(cmd))
		{
			if (!stateService.isDebugLogging())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[OSRS TCG] ::tcg-give requires debug mode",
					null);
				return;
			}
			handleGiveCardCommand(event);
			return;
		}

		if ("tcg-apex".equalsIgnoreCase(cmd))
		{
			if (!stateService.isDebugLogging())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[OSRS TCG] ::tcg-apex requires debug mode",
					null);
				return;
			}
			handleOpenFirstBoosterCommand(true);
			return;
		}

		if ("tcg-complete".equalsIgnoreCase(cmd))
		{
			if (!stateService.isDebugLogging())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[OSRS TCG] ::tcg-complete requires debug mode",
					null);
				return;
			}
			handleCompleteAlbumCommand();
			return;
		}

		if ("tcg-open".equalsIgnoreCase(cmd))
		{
			handleOpenFirstBoosterCommand(false);
			return;
		}
	}

	private void handleOpenFirstBoosterCommand(boolean forcedApex)
	{
		if (packCatalog.getBoosters().isEmpty())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSRS TCG] No booster packs loaded.", null);
			return;
		}

		tcgPanel.beginPackRevealSidebarFreeze();
		HashSet<CardCollectionKey> preOwned = new HashSet<>(stateService.getState().getCollectionState().getOwnedCards().keySet());
		boolean showScrollWheelHint = stateService.getState().getEconomyState().getOpenedPacks() == 0L;
		var result = forcedApex
			? packOpeningService.buyAndOpenApexPackForDebug(packCatalog.getBoosters().get(0))
			: packOpeningService.buyAndOpenPack(packCatalog.getBoosters().get(0));
		if (!result.isSuccess())
		{
			tcgPanel.clearPackRevealSidebarFreeze();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSRS TCG] " + result.getMessage(), null);
			tcgPanel.refresh();
			return;
		}

		String openedLine = forcedApex
			? String.format(Locale.US, "[OSRS TCG] Opened apex pack for %s credits. New balance: %s. Pulled %s cards.",
				NumberFormatting.format(result.getPackPrice()), NumberFormatting.format(result.getCreditsAfter()),
				NumberFormatting.format(result.getPulls().size()))
			: String.format(Locale.US, "[OSRS TCG] Opened pack for %s credits. New balance: %s. Pulled %s cards.",
				NumberFormatting.format(result.getPackPrice()), NumberFormatting.format(result.getCreditsAfter()),
				NumberFormatting.format(result.getPulls().size()));
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", openedLine, null);
		packRevealService.startReveal(result.getPulls(), preOwned, result.getBoosterDisplayName(),
			result.getBoosterPackId(), showScrollWheelHint, result.isApexPack());
		tcgPanel.refresh();
	}

	private void handleCompleteAlbumCommand()
	{
		cardDatabase.load();
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
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] No cards loaded from Card.json.", null);
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

		collectionAlbumManager.refreshIfVisible();
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			String.format(Locale.US, "[OSRS TCG] Added 1× each catalog card (%s cards).",
				NumberFormatting.format(added)),
			null);
		tcgPanel.refresh();
	}

	private void handleGiveCardCommand(CommandExecuted event)
	{
		String[] arguments = event.getArguments();
		if (arguments == null || arguments.length == 0)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Usage: ::tcg-give <card name>  or  ::tcg-give <card name> (foil)", null);
			return;
		}

		String joined = Arrays.stream(arguments)
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.joining(" "));
		if (joined.isEmpty())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Usage: ::tcg-give <card name>  or  ::tcg-give <card name> (foil)", null);
			return;
		}

		boolean foil = TCG_GIVE_FOIL_SUFFIX.matcher(joined).find();
		String cardQuery = TCG_GIVE_FOIL_SUFFIX.matcher(joined).replaceFirst("").trim();
		if (cardQuery.isEmpty())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Usage: ::tcg-give <card name>  or  ::tcg-give <card name> (foil)", null);
			return;
		}

		Optional<String> resolved = cardDatabase.getCards().stream()
			.filter(Objects::nonNull)
			.map(CardDefinition::getName)
			.filter(Objects::nonNull)
			.filter(n -> n.trim().equalsIgnoreCase(cardQuery))
			.findFirst()
			.map(n -> n.trim());

		if (!resolved.isPresent())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				String.format(Locale.US, "[OSRS TCG] No card named \"%s\" in Card.json.", cardQuery), null);
			return;
		}

		String canonicalName = resolved.get();
		String who = client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null
			? Text.sanitize(client.getLocalPlayer().getName())
			: "";
		stateService.addCard(canonicalName, foil, 1, OwnedCardInstance.withDebugPullMetadataPrefix(who),
			System.currentTimeMillis());
		collectionAlbumManager.refreshIfVisible();
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			String.format(Locale.US, "[OSRS TCG] Gave 1× %s%s.", canonicalName, foil ? " (foil)" : ""), null);
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
	OsrsTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsTcgConfig.class);
	}
}

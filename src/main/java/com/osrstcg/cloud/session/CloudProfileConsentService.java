package com.osrstcg.cloud.session;

import com.osrstcg.util.TcgPluginGameMessages;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudConnectionState;
import com.osrstcg.cloud.catalog.CardCatalogService;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.state.TcgStateService;

/**
 * Create-profile consent and adopting an already-migrated server account.
 * Local save upload / {@code POST /me/migrate} is handled outside the plugin.
 */
@Slf4j
final class CloudProfileConsentService
{
	private final CloudSessionService session;
	private final CloudCollectionSyncService collectionSync;
	private final Client client;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final ProfileKeyHasher profileKeyHasher;
	private final TcgStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final PackCatalogService packCatalogService;
	private final CardCatalogService cardCatalogService;
	private final ActivityConfigService activityConfigService;

	CloudProfileConsentService(
		CloudSessionService session,
		CloudCollectionSyncService collectionSync,
		Client client,
		CloudApiClient api,
		CloudTokenStore tokens,
		ProfileKeyHasher profileKeyHasher,
		TcgStateService stateService,
		ChatMessageManager chatMessageManager,
		PackCatalogService packCatalogService,
		CardCatalogService cardCatalogService,
		ActivityConfigService activityConfigService)
	{
		this.session = session;
		this.collectionSync = collectionSync;
		this.client = client;
		this.api = api;
		this.tokens = tokens;
		this.profileKeyHasher = profileKeyHasher;
		this.stateService = stateService;
		this.chatMessageManager = chatMessageManager;
		this.packCatalogService = packCatalogService;
		this.cardCatalogService = cardCatalogService;
		this.activityConfigService = activityConfigService;
	}

	/** Pair/refresh if needed, set local consent, then finish cloud setup. Call off the EDT. */
	void createProfile() throws Exception
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			throw new IllegalStateException("Log in to RuneScape first");
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			throw new IllegalStateException("Waiting for account");
		}
		String displayName = session.resolveDisplayName();
		if (displayName == null)
		{
			throw new IllegalStateException("Waiting for display name");
		}
		String profileHash = profileKeyHasher.currentProfileKeyHash();
		if (profileHash == null)
		{
			throw new IllegalStateException("No RuneLite profile key");
		}

		try (AutoCloseable ignored = api.openConsentTraffic())
		{
			createProfileAllowed(accountHash, displayName, profileHash);
		}
	}

	private void createProfileAllowed(
		long accountHash,
		String displayName,
		String profileHash) throws Exception
	{
		if (!session.isSessionActive())
		{
			session.setState(CloudConnectionState.CONNECTING, "Connecting…");
			api.getHealth();
			if (tokens.hasRefreshToken())
			{
				api.applyTokenResponse(api.refresh(tokens.getRefreshToken(), profileHash));
			}
			else
			{
				session.pairSession(displayName, profileHash, accountHash);
			}
			adoptServerMigrationIfNeeded();
			session.setState(CloudConnectionState.CONNECTED,
				tokens.isMigrated() ? "Connected" : "Create a profile");
		}

		if (tokens.isMigrated())
		{
			finishConsentSuccess();
			return;
		}

		tokens.setMigrated(true);
		TcgPluginGameMessages.queueGameMessage(chatMessageManager,
			"[OSRS TCG] Created cloud profile.");
		finishConsentSuccess();
	}

	void finishConsentSuccess() throws Exception
	{
		collectionSync.refreshLocalCacheFromCloud();

		if (session.isAccountLocked())
		{
			return;
		}
		session.settleHiscoresAfterCloudLogin();
		if (session.isAccountLocked())
		{
			return;
		}
		session.setState(CloudConnectionState.CONNECTED, "Connected");
		session.deleteObsoleteLocalCaches();
		packCatalogService.refreshOnLogin();
		cardCatalogService.refreshNow();
		activityConfigService.refreshOnLogin();
	}

	void adoptServerMigrationIfNeeded() throws Exception
	{
		if (tokens.isMigrated() || tokens.getAccessToken() == null)
		{
			return;
		}
		if (!session.hasLocalProgress())
		{
			return;
		}

		JsonObject stateJson = api.getState();
		CloudPlayerStateParser.ParsedCloudPlayerState parsed =
			collectionSync.loadCloudPlayerStateWithCards(stateJson);
		boolean serverMigrated = (parsed.migratedAt != null && !parsed.migratedAt.isBlank())
			|| !parsed.cards.isEmpty();
		if (!serverMigrated)
		{
			return;
		}

		log.info("Cloud account already migrated; adopting server collection and clearing consent gate");
		tokens.setMigrated(true);
		if (parsed.accountStatus != null && !parsed.accountStatus.isBlank())
		{
			session.applyAccountStatus(parsed.accountStatus);
		}
		stateService.replaceCloudGroupKey(parsed.groupKey);
		stateService.replaceFromCloudState(
			com.osrstcg.state.CollectionState.copyOf(parsed.cards),
			parsed.economy,
			parsed.totalCreditsGained,
			parsed.revision,
			parsed.stateHash,
			parsed.collectionHash,
			parsed.sidebarStats);
		session.deleteObsoleteLocalCaches();
		cardCatalogService.refreshNow();
	}
}

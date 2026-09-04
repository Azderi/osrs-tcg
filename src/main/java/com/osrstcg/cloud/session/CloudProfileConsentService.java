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
 * Handles the one-time cloud profile creation/consent flow: pairing or refreshing a session,
 * marking the profile migrated, and adopting an already-migrated server collection when local
 * progress exists but this profile hasn't consented yet. Blocking: methods issue synchronous HTTP
 * calls and must not run on the client/EDT thread.
 */
@Slf4j
final class CloudProfileConsentService
{
	private final CloudSessionService session;
	private final CloudCollectionSyncService collectionSync;
	private final HiscoresSettleService hiscoresSettle;
	private final Client client;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final ProfileKeyHasher profileKeyHasher;
	private final TcgStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final PackCatalogService packCatalogService;
	private final CardCatalogService cardCatalogService;
	private final ActivityConfigService activityConfigService;
/** Wires collaborators; no side effects. */
	CloudProfileConsentService(
		CloudSessionService session,
		CloudCollectionSyncService collectionSync,
		HiscoresSettleService hiscoresSettle,
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
		this.hiscoresSettle = hiscoresSettle;
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
/**
	 * Creates (or completes consent for) the cloud profile for the current RuneScape account: requires
	 * being logged in with a known account hash, display name, and RuneLite profile key. Runs the
	 * pairing/migration flow inside {@link CloudApiClient#openConsentTraffic()} so consent-gated
	 * traffic is permitted for its duration.
	 */
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
/**
	 * Ensures a session is active (refreshing or pairing as needed), adopts an already-migrated
	 * server collection if applicable, then marks the profile migrated and finishes consent if it
	 * wasn't already.
	 */
	private void createProfileAllowed(
		long accountHash,
		String displayName,
		String profileHash) throws Exception
	{
		if (!session.isSessionActive())
		{
			session.setState(CloudConnectionState.CONNECTING, "Connecting…");
			api.getHealth();
			if (CloudTokenStore.shouldClearForAccount(tokens.getBoundAccountHash(), tokens.hasRefreshToken(), accountHash))
			{
				tokens.clear();
			}
			if (tokens.hasRefreshToken())
			{
				api.applyTokenResponse(api.refresh(tokens.getRefreshToken(), profileHash), accountHash);
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
		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
			"Created cloud profile.");
		finishConsentSuccess();
	}
/**
	 * Post-consent bring-up: refreshes local cache from cloud, settles offline hiscores, marks the
	 * session connected, clears obsolete local caches, and refreshes pack/card catalogs and activity
	 * config. Bails out early if the account becomes locked (banned/quarantined) partway through.
	 */
	void finishConsentSuccess() throws Exception
	{
		collectionSync.refreshLocalCacheFromCloud();

		if (session.isAccountLocked())
		{
			return;
		}
		hiscoresSettle.settleAfterCloudLogin();
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
/**
	 * If this profile hasn't recorded migration locally but has local progress (credits/packs/cards)
	 * and has an access token, checks the server state: if the server shows the account already
	 * migrated (has a migration timestamp or cards), adopts that server collection/economy locally
	 * and marks migrated, skipping the consent prompt. No-op otherwise.
	 */
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

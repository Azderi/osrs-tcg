package com.osrstcg.cloud.session;

import com.osrstcg.state.TcgState;
import com.osrstcg.persist.TcgStateCodec;
import com.osrstcg.persist.TcgStateStorageEncoding;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.TcgPluginGameMessages;
import com.google.gson.JsonObject;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.CloudConnectionState;
import com.osrstcg.cloud.catalog.CardCatalogService;
import com.osrstcg.cloud.catalog.PackCatalogService;
import net.runelite.client.config.ConfigManager;

/** Upload, finish, and import-watch poll for local→cloud collection migrate. */
@Slf4j
final class CloudMigrationService
{
	private static final String CONFIG_GROUP = "osrstcg";
	private static final String[] LEGACY_SAVE_CONFIG_KEYS = {
		"state", "hash", "stateBackup", "hashBackup", "stateWrittenAt"
	};

	private final CloudSessionService session;
	private final CloudCollectionSyncService collectionSync;
	private final Client client;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final ProfileKeyHasher profileKeyHasher;
	private final TcgStateService stateService;
	private final TcgStateCodec stateCodec;
	private final ChatMessageManager chatMessageManager;
	private final PackCatalogService packCatalogService;
	private final CardCatalogService cardCatalogService;
	private final ActivityConfigService activityConfigService;
	private final ScheduledExecutorService scheduler;
	private final AtomicBoolean forceStatePullOnce;
	private final AtomicBoolean migrateImportWatchScheduled;
	private final AtomicBoolean deferCollectionPullForMigrateImport;
	private final ConfigManager configManager;

	CloudMigrationService(
		CloudSessionService session,
		CloudCollectionSyncService collectionSync,
		Client client,
		CloudApiClient api,
		CloudTokenStore tokens,
		ProfileKeyHasher profileKeyHasher,
		TcgStateService stateService,
		TcgStateCodec stateCodec,
		ChatMessageManager chatMessageManager,
		PackCatalogService packCatalogService,
		CardCatalogService cardCatalogService,
		ActivityConfigService activityConfigService,
		ScheduledExecutorService scheduler,
		AtomicBoolean forceStatePullOnce,
		AtomicBoolean migrateImportWatchScheduled,
		AtomicBoolean deferCollectionPullForMigrateImport,
		ConfigManager configManager)
	{
		this.session = session;
		this.collectionSync = collectionSync;
		this.client = client;
		this.api = api;
		this.tokens = tokens;
		this.profileKeyHasher = profileKeyHasher;
		this.stateService = stateService;
		this.stateCodec = stateCodec;
		this.chatMessageManager = chatMessageManager;
		this.packCatalogService = packCatalogService;
		this.cardCatalogService = cardCatalogService;
		this.activityConfigService = activityConfigService;
		this.scheduler = scheduler;
		this.forceStatePullOnce = forceStatePullOnce;
		this.migrateImportWatchScheduled = migrateImportWatchScheduled;
		this.deferCollectionPullForMigrateImport = deferCollectionPullForMigrateImport;
		this.configManager = configManager;
	}

	void migrateLocalCollection(boolean requireCollectionUpload) throws Exception
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
			migrateLocalCollectionAllowed(requireCollectionUpload, accountHash, displayName, profileHash);
		}
	}

	private void migrateLocalCollectionAllowed(
		boolean requireCollectionUpload,
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
				tokens.isMigrated() || !session.hasLocalProgress() ? "Connected" : "Migrate your collection");
		}

		if (tokens.isMigrated())
		{
			finishMigrationSuccess();
			return;
		}
		if (!session.hasLocalProgress())
		{
			if (requireCollectionUpload)
			{
				throw new IllegalStateException(
					"Nothing to upload - save is empty after load (debug-mode saves are blocked outside developer mode)");
			}
			tokens.setMigrated(true);
			TcgPluginGameMessages.queueGameMessage(chatMessageManager,
				"[OSRS TCG] Created empty cloud profile (nothing local to upload).");
			finishMigrationSuccess();
			return;
		}

		uploadLocalCollection(accountHash);
		finishMigrationSuccess();
	}

	void finishMigrationSuccess() throws Exception
	{
		boolean serverMigrated = false;
		boolean importQueued = false;
		try
		{
			JsonObject migrateStatus = api.getMigrateStatus();
			serverMigrated = migrateStatus.has("migrated")
				&& !migrateStatus.get("migrated").isJsonNull()
				&& migrateStatus.get("migrated").getAsBoolean();
			String ms = migrateStatus.has("migrateStatus") && !migrateStatus.get("migrateStatus").isJsonNull()
				? migrateStatus.get("migrateStatus").getAsString()
				: "";
			importQueued = "pending".equals(ms) || "processing".equals(ms);
		}
		catch (Exception ex)
		{
			log.debug("Could not read migrate status during finish", ex);
		}

		if (serverMigrated)
		{
			deferCollectionPullForMigrateImport.set(false);
			forceStatePullOnce.set(true);
			collectionSync.refreshLocalCacheFromCloud();
		}
		else if (importQueued || deferCollectionPullForMigrateImport.get())
		{
			deferCollectionPullForMigrateImport.set(true);
			try
			{
				JsonObject stats = api.getStats();
				collectionSync.applySidebarStats(stats);
			}
			catch (Exception ex)
			{
				log.debug("Could not refresh sidebar stats after migrate accept", ex);
			}
			scheduleMigrateImportWatch();
		}
		else
		{
			collectionSync.refreshLocalCacheFromCloud();
		}

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

		log.info("Cloud account already migrated; adopting server collection and clearing migrate gate");
		tokens.setMigrated(true);
		if (parsed.accountStatus != null && !parsed.accountStatus.isBlank())
		{
			session.applyAccountStatus(parsed.accountStatus);
		}
		stateService.replaceFromCloudState(
			com.osrstcg.state.CollectionState.copyOf(parsed.cards),
			parsed.economy,
			parsed.totalCreditsGained,
			parsed.revision,
			parsed.stateHash,
			parsed.sidebarStats);
		session.deleteObsoleteLocalCaches();
		cardCatalogService.refreshNow();
	}

	private void uploadLocalCollection(long accountHash) throws Exception
	{
		TcgState local = stateService.getState();
		String profileBlob = TcgStateStorageEncoding.encodeLegacyV2(stateCodec.toJson(local));
		if (profileBlob == null || profileBlob.isEmpty())
		{
			throw new IllegalStateException("Failed to encode local profile for cloud migrate");
		}

		JsonObject body = new JsonObject();
		body.addProperty("accountHash", Long.toString(accountHash));
		body.addProperty("profileBlob", profileBlob);

		try
		{
			JsonObject result = api.migrate(body);
			tokens.setMigrated(true);
			unsetLegacySaveConfigKeys();
			boolean queued = result != null
				&& result.has("migrateStatus")
				&& !result.get("migrateStatus").isJsonNull()
				&& ("pending".equals(result.get("migrateStatus").getAsString())
					|| "processing".equals(result.get("migrateStatus").getAsString()));
			if (result != null && result.has("status") && !result.get("status").isJsonNull())
			{
				String status = result.get("status").getAsString();
				if (!"queued".equals(status))
				{
					session.applyAccountStatus(status);
				}
			}
			if (queued)
			{
				deferCollectionPullForMigrateImport.set(true);
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Upload accepted - beta cards will appear when the server finishes importing.");
				scheduleMigrateImportWatch();
			}
			else
			{
				deferCollectionPullForMigrateImport.set(false);
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Local collection migrated to cloud.");
			}
		}
		catch (CloudApiException ex)
		{
			if ("already_migrated".equals(ex.getCode())
				|| "migrate_not_empty".equals(ex.getCode())
				|| "migrate_already_imported".equals(ex.getCode()))
			{
				tokens.setMigrated(true);
				return;
			}
			throw ex;
		}
	}

	private void unsetLegacySaveConfigKeys()
	{
		if (configManager == null)
		{
			return;
		}
		for (String key : LEGACY_SAVE_CONFIG_KEYS)
		{
			configManager.unsetRSProfileConfiguration(CONFIG_GROUP, key);
			configManager.unsetConfiguration(CONFIG_GROUP, key);
		}
	}

	private void scheduleMigrateImportWatch()
	{
		if (!migrateImportWatchScheduled.compareAndSet(false, true))
		{
			return;
		}
		scheduler.schedule(this::pollMigrateImportOnce, 5, TimeUnit.SECONDS);
	}

	private void pollMigrateImportOnce()
	{
		try
		{
			if (tokens.getAccessToken() == null)
			{
				migrateImportWatchScheduled.set(false);
				return;
			}
			JsonObject status = api.getMigrateStatus();
			boolean migrated = status.has("migrated")
				&& !status.get("migrated").isJsonNull()
				&& status.get("migrated").getAsBoolean();
			String migrateStatus = status.has("migrateStatus") && !status.get("migrateStatus").isJsonNull()
				? status.get("migrateStatus").getAsString()
				: "";
			if (migrated || "completed".equals(migrateStatus))
			{
				migrateImportWatchScheduled.set(false);
				deferCollectionPullForMigrateImport.set(false);
				if (status.has("status") && !status.get("status").isJsonNull())
				{
					session.applyAccountStatus(status.get("status").getAsString());
				}
				forceStatePullOnce.set(true);
				collectionSync.refreshLocalCacheFromCloud();
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Beta collection imported to cloud.");
				return;
			}
			if ("failed".equals(migrateStatus))
			{
				migrateImportWatchScheduled.set(false);
				deferCollectionPullForMigrateImport.set(false);
				String msg = status.has("errorMessage") && !status.get("errorMessage").isJsonNull()
					? status.get("errorMessage").getAsString()
					: "Migration failed on server";
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] Migration import failed: " + msg);
				return;
			}
			if ("pending".equals(migrateStatus) || "processing".equals(migrateStatus))
			{
				scheduler.schedule(this::pollMigrateImportOnce, 10, TimeUnit.SECONDS);
				return;
			}
			migrateImportWatchScheduled.set(false);
		}
		catch (Exception ex)
		{
			log.warn("Migrate import watch poll failed; retrying", ex);
			scheduler.schedule(this::pollMigrateImportOnce, 15, TimeUnit.SECONDS);
		}
	}
}

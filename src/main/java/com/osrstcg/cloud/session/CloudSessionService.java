package com.osrstcg.cloud.session;

import com.osrstcg.state.TcgState;
import com.osrstcg.persist.TcgStateCodec;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.TcgPluginGameMessages;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.CloudConnectionState;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.catalog.CardCatalogService;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.trade.TradeCloudService;

@Slf4j
@Singleton
public final class CloudSessionService
{
	private final Client client;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final ProfileKeyHasher profileKeyHasher;
	private final TcgStateService stateService;
	private final ConfigManager configManager;
	private final ChatMessageManager chatMessageManager;
	private final PackCatalogService packCatalogService;
	private final CardCatalogService cardCatalogService;
	private final CardImageCacheService cardImageCacheService;
	private final ActivityConfigService activityConfigService;
	private final RestrictedWorldGuard restrictedWorldGuard;
	private final Provider<TradeCloudService> tradeCloudProvider;
	private final Provider<CreditAttestQueue> creditAttestQueueProvider;
	private final CloudCollectionPager collectionPager;
	private final CloudCollectionSyncService collectionSync;
	private final HiscoresSettleService hiscoresSettle;
	private final CloudMigrationService migration;

	private final AtomicReference<CloudConnectionState> connectionState =
		new AtomicReference<>(CloudConnectionState.DISCONNECTED);
	private final AtomicReference<String> statusMessage = new AtomicReference<>("Disconnected");
	private final AtomicReference<Runnable> statusListener = new AtomicReference<>(null);
	/** At most one hiscores settle attempt outcome per cloud login (retries for 503 use a separate flag). */
	private final AtomicBoolean hiscoresSettledThisLogin = new AtomicBoolean(false);
	private final AtomicBoolean hiscoresSettleRetryScheduled = new AtomicBoolean(false);
	/** When true, next collection reconcile always pulls {@code /me/state}. */
	private final AtomicBoolean forceStatePullOnce = new AtomicBoolean(false);
	/** Background poll after async migrate upload until server import finishes. */
	private final AtomicBoolean migrateImportWatchScheduled = new AtomicBoolean(false);
	/** Skip replacing local collection with empty cloud while a migrate job is queued. */
	private final AtomicBoolean deferCollectionPullForMigrateImport = new AtomicBoolean(false);
	/** Server banned this account for the current login; cleared on logout. */
	private final AtomicBoolean accountBanned = new AtomicBoolean(false);
	/** Server quarantined this account for the current login; cleared on logout. */
	private final AtomicBoolean accountQuarantined = new AtomicBoolean(false);
	/** Run after ban/quarantine lock; registered by plugin startup to avoid Guice cycles. */
	private final List<Runnable> accountLockCleanups = new CopyOnWriteArrayList<>();

	public static final String DEBUG_MODE_STATUS = "Debug mode";
	public static final String ACCOUNT_BANNED_STATUS =
		"Your account has been banned. Check the account panel for more information.";
	public static final String ACCOUNT_QUARANTINED_STATUS =
		"Your account is quarantined. Check the account panel for more information.";

	@Inject
	CloudSessionService(
		Client client,
		CloudApiClient api,
		CloudTokenStore tokens,
		ProfileKeyHasher profileKeyHasher,
		TcgStateService stateService,
		TcgStateCodec stateCodec,
		ConfigManager configManager,
		ChatMessageManager chatMessageManager,
		PackCatalogService packCatalogService,
		CardCatalogService cardCatalogService,
		CardImageCacheService cardImageCacheService,
		ActivityConfigService activityConfigService,
		RestrictedWorldGuard restrictedWorldGuard,
		ScheduledExecutorService scheduler,
		Provider<TradeCloudService> tradeCloudProvider,
		Provider<CreditAttestQueue> creditAttestQueueProvider,
		TcgPublicStatsCalculator publicStatsCalculator)
	{
		this.client = client;
		this.api = api;
		this.tokens = tokens;
		this.profileKeyHasher = profileKeyHasher;
		this.stateService = stateService;
		this.configManager = configManager;
		this.chatMessageManager = chatMessageManager;
		this.packCatalogService = packCatalogService;
		this.cardCatalogService = cardCatalogService;
		this.cardImageCacheService = cardImageCacheService;
		this.activityConfigService = activityConfigService;
		this.restrictedWorldGuard = restrictedWorldGuard;
		this.tradeCloudProvider = tradeCloudProvider;
		this.creditAttestQueueProvider = creditAttestQueueProvider;
		this.collectionPager = new CloudCollectionPager(api);
		this.collectionSync = new CloudCollectionSyncService(
			this, api, tokens, stateService, chatMessageManager, creditAttestQueueProvider,
			publicStatsCalculator, collectionPager, forceStatePullOnce, deferCollectionPullForMigrateImport);
		this.hiscoresSettle = new HiscoresSettleService(
			client, api, tokens, restrictedWorldGuard, scheduler, chatMessageManager, tradeCloudProvider,
			collectionSync::applySidebarStats, hiscoresSettledThisLogin, hiscoresSettleRetryScheduled,
			this::needsCloudConsent, this::isAccountLocked, this::isDebugModePaused);
		this.migration = new CloudMigrationService(
			this, collectionSync, client, api, tokens, profileKeyHasher, stateService, stateCodec,
			chatMessageManager, packCatalogService, cardCatalogService, activityConfigService, scheduler,
			forceStatePullOnce, migrateImportWatchScheduled, deferCollectionPullForMigrateImport,
			configManager);
		api.setStaleRefreshHandler(this::handleStaleRefresh);
		api.setAccountLockHandler(this::noteLockFromApiException);
	}

	/** Invoked when a mid-session refresh finds revoked credentials - no auto re-pair. */
	private void handleStaleRefresh()
	{
		packCatalogService.clear();
		cardCatalogService.resetLoginFetchGate();
		activityConfigService.stopQuietPoll();
		hiscoresSettle.clearGate();
		if (needsCloudConsent())
		{
			setState(CloudConnectionState.DISCONNECTED, consentWaitingMessage());
		}
		else
		{
			setState(CloudConnectionState.DISCONNECTED, "Disconnected");
		}
	}

	public CloudConnectionState getConnectionState()
	{
		return connectionState.get();
	}

	public String getStatusMessage()
	{
		return statusMessage.get();
	}

	/** Paired/refreshed with a usable access token (migration may still be pending). */
	public boolean isSessionActive()
	{
		return connectionState.get() == CloudConnectionState.CONNECTED && tokens.getAccessToken() != null;
	}

	/**
	 * Cloud gameplay ready: session active, migrated (consent accepted), not on a blocked world type,
	 * not account-banned/quarantined, and not paused for Overview debug mode.
	 */
	public boolean isReady()
	{
		return isSessionActive()
			&& !needsCloudConsent()
			&& !isRestrictedWorld()
			&& !isAccountLocked()
			&& !isDebugModePaused();
	}

	/**
	 * True when credit events may be buffered with optimistic local credits even if the
	 * session is offline ({@link #isReady()} false). Requires consent; excluded when
	 * banned/quarantined, on a restricted world, or in Overview debug pause.
	 */
	public boolean canCollectAttests()
	{
		return !needsCloudConsent()
			&& !isAccountLocked()
			&& !isRestrictedWorld()
			&& !isDebugModePaused();
	}

	/**
	 * Soft status while a consented client is offline and waiting for the 10-minute reconnect timer.
	 * No-op unless currently {@link CloudConnectionState#ERROR} or disconnected with collect allowed.
	 */
	public void noteOfflineReconnectScheduled()
	{
		if (!canCollectAttests() || isReady())
		{
			return;
		}
		CloudConnectionState state = connectionState.get();
		if (state != CloudConnectionState.ERROR && state != CloudConnectionState.DISCONNECTED)
		{
			return;
		}
		setState(state, "Cloud unreachable - retrying in 5-15m");
	}

	public boolean isRestrictedWorld()
	{
		return restrictedWorldGuard != null && restrictedWorldGuard.isRestricted();
	}

	/**
	 * True after the server reports {@code banned: true} (e.g. attest). Cleared on logout.
	 * Tokens are kept so the account panel can still open.
	 */
	public boolean isAccountBanned()
	{
		return accountBanned.get();
	}

	/**
	 * True after the server reports {@code quarantined: true} (e.g. attest). Cleared on logout.
	 * Tokens are kept so the account panel can still open.
	 */
	public boolean isAccountQuarantined()
	{
		return accountQuarantined.get();
	}

	/** Banned or quarantined for this login - full sidebar lock, no cloud gameplay. */
	public boolean isAccountLocked()
	{
		return isAccountBanned() || isAccountQuarantined();
	}

	/**
	 * True when the website account panel can be opened (active session, or locked with tokens retained).
	 */
	public boolean canOpenAccountPanel()
	{
		if (tokens.getAccessToken() == null || needsCloudConsent() || isRestrictedWorld())
		{
			return false;
		}
		return isSessionActive() || isAccountLocked();
	}

	/** Overview debug mode: cloud fully paused (yellow status). */
	public boolean isDebugModePaused()
	{
		return stateService.isDebugLogging();
	}

	/** Pin / restore card catalog for debug tools (memory, disk, or live public fetch). */
	public void ensureDebugCardCatalog()
	{
		cardCatalogService.ensureCachedCatalogForDebug();
	}

	/**
	 * Mark the sidebar as paused on a blocked world type (yellow). Does not clear tokens.
	 */
	public void enterRestrictedWorld()
	{
		hiscoresSettle.clearGate();
		activityConfigService.stopQuietPoll();
		String detail = restrictedWorldGuard == null ? "" : restrictedWorldGuard.describeBlockedTypes();
		String message = detail.isEmpty()
			? RestrictedWorldGuard.STATUS_MESSAGE
			: RestrictedWorldGuard.STATUS_MESSAGE + " (" + detail + ")";
		setState(CloudConnectionState.DISCONNECTED, message);
	}

	/**
	 * Lock the plugin for this login after the server reports a ban. Stops cloud traffic, keeps
	 * tokens for {@code webCode} / account panel, and shows a red disconnected status until logout.
	 */
	public void enterAccountBanned()
	{
		enterAccountLock(accountBanned, ACCOUNT_BANNED_STATUS, "banned");
	}

	/**
	 * Lock the plugin for this login after the server reports quarantine. Same full-sidebar treatment
	 * as a ban (account panel still available). Ban takes precedence if already banned.
	 */
	public void enterAccountQuarantined()
	{
		if (isAccountBanned())
		{
			return;
		}
		enterAccountLock(accountQuarantined, ACCOUNT_QUARANTINED_STATUS, "quarantined");
	}

	private void enterAccountLock(AtomicBoolean flag, String statusMessage, String kind)
	{
		boolean already = flag.getAndSet(true);
		pauseCloudTrafficForAccountLock();
		setState(CloudConnectionState.DISCONNECTED, statusMessage);
		if (!already)
		{
			log.warn("Account {}; cloud traffic stopped until logout", kind);
		}
	}

	void applyAccountStatus(String status)
	{
		if (status == null || status.isBlank())
		{
			return;
		}
		tokens.setAccountStatus(status);
		String normalized = status.trim();
		if ("banned".equalsIgnoreCase(normalized))
		{
			enterAccountBanned();
		}
		else if ("quarantined".equalsIgnoreCase(normalized))
		{
			enterAccountQuarantined();
		}
	}

	/**
	 * Register cleanup to run when the account is banned or quarantined (e.g. stop credit tracking).
	 * Registered from {@link com.osrstcg.OsrsTcgPlugin} startup to avoid a Guice cycle with
	 * {@link com.osrstcg.credit.CreditAwardService}.
	 */
	public void registerAccountLockCleanup(Runnable cleanup)
	{
		if (cleanup != null)
		{
			accountLockCleanups.add(cleanup);
		}
	}

	private void pauseCloudTrafficForAccountLock()
	{
		CreditAttestQueue attestQueue = creditAttestQueueProvider.get();
		attestQueue.stop();
		attestQueue.discardPending();
		for (Runnable cleanup : accountLockCleanups)
		{
			try
			{
				cleanup.run();
			}
			catch (Exception ex)
			{
				log.warn("Account lock cleanup failed", ex);
			}
		}
		stateService.clearOptimisticCredits();
		tradeCloudProvider.get().stop();
		activityConfigService.stopQuietPoll();
		hiscoresSettle.clearGate();
		packCatalogService.clear();
	}

	/** Apply ban/quarantine locks from an attest (or similar) JSON body. */
	public void noteAttestBanFlags(JsonObject response)
	{
		if (response == null)
		{
			return;
		}
		if (response.has("banned") && !response.get("banned").isJsonNull()
			&& response.get("banned").getAsBoolean())
		{
			enterAccountBanned();
			return;
		}
		if (response.has("quarantined") && !response.get("quarantined").isJsonNull()
			&& response.get("quarantined").getAsBoolean())
		{
			enterAccountQuarantined();
		}
	}

	/** Lock from HTTP {@code banned} / {@code account_banned} / {@code quarantined} error codes (pack open, attest, etc.). */
	public void noteLockFromApiException(CloudApiException ex)
	{
		if (ex == null)
		{
			return;
		}
		if (ex.isAccountBanned())
		{
			enterAccountBanned();
			return;
		}
		if (ex.isAccountQuarantined())
		{
			enterAccountQuarantined();
		}
	}

	/**
	 * Pause all cloud traffic for Overview debug mode (yellow). Flushes any pending attests first,
	 * then discards leftovers. Tokens are kept so leaving debug can reconnect without re-pairing.
	 * Call after {@link TcgStateService#setDebugLogging(true)}, off the EDT / client thread.
	 */
	public void enterDebugMode()
	{
		CreditAttestQueue attestQueue = creditAttestQueueProvider.get();
		try
		{
			attestQueue.flushBlocking();
		}
		catch (Exception ex)
		{
			log.warn("Credit attest flush before debug mode failed", ex);
		}
		pauseForDebugMode(true);
	}

	/**
	 * Apply debug-mode cloud pause without a network flush (plugin start / already-paused).
	 * Call after {@link TcgStateService#setDebugLogging(true)}.
	 */
	public void pauseForDebugMode()
	{
		pauseForDebugMode(false);
	}

	private void pauseForDebugMode(boolean resetLocalProgress)
	{
		CreditAttestQueue attestQueue = creditAttestQueueProvider.get();
		attestQueue.stop();
		attestQueue.discardPending();
		stateService.clearOptimisticCredits();
		tradeCloudProvider.get().stop();
		activityConfigService.stopQuietPoll();
		hiscoresSettle.clearGate();
		packCatalogService.clear();
		// Public catalog fetch is still allowed; pin whatever we have for ::tcg-* tools.
		cardCatalogService.ensureCachedCatalogForDebug();
		stateService.clearCloudCollectionStatsCache();
		stateService.clearCloudGroupKey();
		if (resetLocalProgress)
		{
			// Clean local sandbox (credits, opened packs, collection) when toggling debug on.
			stateService.resetProgressForCloudResync();
		}
		setState(CloudConnectionState.DISCONNECTED, DEBUG_MODE_STATUS);
	}

	/**
	 * Leave debug mode: wipe local progress, reconnect, and force-pull cloud state.
	 * Call after {@link TcgStateService#setDebugLogging(false)}. Runs network work on the caller thread.
	 */
	public synchronized void exitDebugModeAndResync()
	{
		CreditAttestQueue attestQueue = creditAttestQueueProvider.get();
		attestQueue.stop();
		attestQueue.discardPending();
		tradeCloudProvider.get().stop();
		cardCatalogService.clearDebugCatalogPin();
		stateService.resetProgressForCloudResync();
		forceStatePullOnce.set(true);
		ensureSession();
		if (isReady())
		{
			attestQueue.start();
			tradeCloudProvider.get().start();
		}
	}

	/**
	 * True when an access token is still available for a teardown attest flush
	 * (logout / shutdown / unload). Unlike {@link #isReady()}, does not require
	 * {@link CloudConnectionState#CONNECTED} so a final flush can run even if the
	 * UI already marked the session disconnected.
	 */
	public boolean canAttestFlush()
	{
		return tokens.getAccessToken() != null && !needsCloudConsent() && !isAccountLocked();
	}

	/**
	 * True until the user accepts Migrate collection / Create profile.
	 * While true, no cloud API traffic should run (except the consent action itself).
	 */
	public boolean needsCloudConsent()
	{
		return !tokens.isMigrated();
	}

	/**
	 * Local pre-cloud collection still needs an explicit migrate upload.
	 * Local-only - no session required (cloud stays offline until the user accepts).
	 * True when in-memory progress exists or the current profile has disk save files.
	 */
	public boolean isMigrationPending()
	{
		return needsCloudConsent() && hasLocalProgressToMigrate();
	}

	/**
	 * No local progress and no disk saves - show Create profile instead of migrate.
	 */
	public boolean needsProfileCreate()
	{
		return needsCloudConsent() && !hasLocalProgressToMigrate();
	}

	private String consentWaitingMessage()
	{
		return isMigrationPending() ? "Migrate your collection" : "Create a profile";
	}

	public void setStatusListener(Runnable listener)
	{
		statusListener.set(listener);
	}

	/**
	 * True when login happened but account hash / display name are not readable yet.
	 * Callers should retry {@link #ensureSession()} on a later game tick.
	 */
	public boolean isWaitingForGameIdentity()
	{
		String message = statusMessage.get();
		return "Waiting for display name".equals(message) || "Waiting for account".equals(message);
	}

	public synchronized void ensureSession()
	{
		if (stateService.isDebugLogging())
		{
			setState(CloudConnectionState.DISCONNECTED, DEBUG_MODE_STATUS);
			return;
		}
		if (isAccountLocked())
		{
			setState(CloudConnectionState.DISCONNECTED,
				isAccountBanned() ? ACCOUNT_BANNED_STATUS : ACCOUNT_QUARANTINED_STATUS);
			return;
		}
		if (restrictedWorldGuard != null && restrictedWorldGuard.isRestricted())
		{
			enterRestrictedWorld();
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			setState(CloudConnectionState.DISCONNECTED, "Log in to RuneScape");
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			setState(CloudConnectionState.DISCONNECTED, "Waiting for account");
			return;
		}
		// No cloud API until the user accepts Migrate collection / Create profile.
		if (needsCloudConsent())
		{
			setState(CloudConnectionState.DISCONNECTED, consentWaitingMessage());
			return;
		}
		String displayName = resolveDisplayName();
		// Refresh / resume only needs account hash + tokens. Display name is required to pair.
		boolean needsDisplayName = !tokens.hasRefreshToken();
		if (needsDisplayName && (displayName == null || displayName.isEmpty()))
		{
			setState(CloudConnectionState.DISCONNECTED, "Waiting for display name");
			return;
		}
		String profileHash = profileKeyHasher.currentProfileKeyHash();
		if (profileHash == null)
		{
			setState(CloudConnectionState.ERROR, "No RuneLite profile key");
			return;
		}

		setState(CloudConnectionState.CONNECTING, "Connecting…");
		try
		{
			api.getHealth();
			if (tokens.hasRefreshToken())
			{
				try
				{
					api.applyTokenResponse(api.refresh(tokens.getRefreshToken(), profileHash));
				}
				catch (CloudApiException refreshEx)
				{
					if (!refreshEx.isStaleRefreshToken())
					{
						throw refreshEx;
					}
					log.info("Clearing stale cloud credentials ({})", refreshEx.getCode());
					tokens.clear();
				}
			}

			if (!tokens.hasRefreshToken())
			{
				if (displayName == null || displayName.isEmpty())
				{
					setState(CloudConnectionState.DISCONNECTED, "Waiting for display name");
					return;
				}
				pairSession(displayName, profileHash, accountHash);
			}

			migration.adoptServerMigrationIfNeeded();
			collectionSync.refreshLocalCacheFromCloud();
			if (isAccountLocked())
			{
				return;
			}
			// Prefer settle before CONNECTED so status listeners starting attest see last_settle_at.
			hiscoresSettle.settleAfterCloudLogin();
			if (isAccountLocked())
			{
				return;
			}
			setState(CloudConnectionState.CONNECTED, "Connected");
			packCatalogService.refreshOnLogin();
			cardCatalogService.refreshOnLogin();
			activityConfigService.refreshOnLogin();
		}
		catch (CloudApiException ex)
		{
			log.warn("Cloud session failed: {} {}", ex.getCode(), ex.getMessage());
			if (isAccountLocked())
			{
				return;
			}
			setState(CloudConnectionState.ERROR, ex.getMessage());
			TcgPluginGameMessages.queueGameMessage(chatMessageManager,
				"[OSRS TCG] Cloud: " + ex.getMessage());
		}
		catch (Exception ex)
		{
			log.warn("Cloud session failed", ex);
			setState(CloudConnectionState.ERROR, "Cloud unreachable");
			TcgPluginGameMessages.queueGameMessage(chatMessageManager,
				"[OSRS TCG] Cloud unreachable");
		}
	}

	/** @return sanitized local player name, or null if not ready yet after login */
	String resolveDisplayName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return null;
		}
		String name = Text.sanitize(client.getLocalPlayer().getName());
		return name == null || name.isEmpty() ? null : name;
	}

	/**
	 * Uploads the local pre-cloud profile to the server. Call off the EDT.
	 * Pairs/logs in first when credentials were cleared or never established.
	 */
	public synchronized void migrateLocalCollection() throws Exception
	{
		migrateLocalCollection(false);
	}

	/**
	 * @param requireCollectionUpload when true (user picked a save / Migrate collection),
	 *     refuse the empty create-profile shortcut so we never mark local migrated without
	 *     {@code POST /me/migrate}.
	 */
	public synchronized void migrateLocalCollection(boolean requireCollectionUpload) throws Exception
	{
		migration.migrateLocalCollection(requireCollectionUpload);
	}

	void deleteObsoleteLocalCaches()
	{
		cardCatalogService.deleteDiskCache();
		cardImageCacheService.deleteObsoleteImageCacheDirs();
	}

	public void disconnectQuietly()
	{
		accountBanned.set(false);
		accountQuarantined.set(false);
		packCatalogService.clear();
		cardCatalogService.resetLoginFetchGate();
		activityConfigService.stopQuietPoll();
		hiscoresSettle.clearGate();
		stateService.clearCloudCollectionStatsCache();
		stateService.clearCloudGroupKey();
		setState(CloudConnectionState.DISCONNECTED, "Disconnected");
	}

	public void snapshotHiscoresOnLogout()
	{
		hiscoresSettle.snapshotOnLogout();
	}

	public void settleHiscoresAfterCloudLogin()
	{
		hiscoresSettle.settleAfterCloudLogin();
	}

	/**
	 * Apply economy / sidebar fields from a {@code /me/stats}-shaped object, an inbox {@code stats}
	 * payload, or pack/attest RPC responses that include the same fields. Missing fields are left
	 * unchanged so partial responses do not wipe the cache.
	 * <p>
	 * Collection overview fields ({@code uniqueOwned}, {@code collectionScore}, …) exclude beta cards.
	 */
	public void applySidebarStats(JsonObject stats)
	{
		collectionSync.applySidebarStats(stats);
	}

	public void maybeReconcileCollectionFromInbox(JsonObject stats)
	{
		collectionSync.maybeReconcileCollectionFromInbox(stats);
	}

	public void refreshCreditsFromServer() throws Exception
	{
		collectionSync.refreshCreditsFromServer();
	}

	public boolean forceRefreshCollectionState()
	{
		return collectionSync.forceRefreshCollectionState();
	}

	void pairSession(String displayName, String profileHash, long accountHash)
		throws CloudApiException, IOException
	{
		JsonObject start = api.pairStart(displayName, profileHash, accountHash);
		api.applyTokenResponse(start);
	}

	boolean hasLocalProgress()
	{
		TcgState local = stateService.getState();
		return local.getEconomyState().getCredits() > 0
			|| local.getEconomyState().getOpenedPacks() > 0
			|| !local.getCollectionState().getOwnedInstances().isEmpty();
	}

	private boolean hasLocalProgressToMigrate()
	{
		return hasLocalProgress() || stateService.hasDiskSaves();
	}

	void setState(CloudConnectionState state, String message)
	{
		connectionState.set(state);
		statusMessage.set(message == null ? "" : message);
		Runnable listener = statusListener.get();
		if (listener != null)
		{
			listener.run();
		}
	}
}

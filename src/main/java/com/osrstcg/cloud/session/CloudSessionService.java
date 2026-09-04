package com.osrstcg.cloud.session;

import com.osrstcg.state.TcgState;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.Text;
import com.osrstcg.cloud.activity.ActivityConfigService;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.CloudConnectionState;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.catalog.CardCatalogService;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.trade.TradeCloudService;
/**
 * Owns the cloud connection lifecycle and account-lock state for the plugin: authenticates/pairs
 * the RuneLite profile with the cloud backend, tracks connection state and status messages, gates
 * traffic behind consent/lock/restricted-world checks, and delegates collection sync, hiscores
 * settling, and profile-consent work to {@link CloudCollectionSyncService}, {@link HiscoresSettleService},
 * and {@link CloudProfileConsentService} respectively. {@link #ensureSession()} is synchronized and
 * blocking (issues synchronous HTTP calls) and must not run on the client/EDT thread.
 */
@Slf4j
@Singleton
public final class CloudSessionService
{
	private static final String WAITING_FOR_ACCOUNT = "Waiting for account";
	private static final String WAITING_FOR_DISPLAY_NAME = "Waiting for display name";
	private static final String CONSENT_WAITING_STATUS = "Create a profile";

	private final Client client;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final ProfileKeyHasher profileKeyHasher;
	private final TcgStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final PackCatalogService packCatalogService;
	private final CardCatalogService cardCatalogService;
	private final CardImageCacheService cardImageCacheService;
	private final ActivityConfigService activityConfigService;
	private final RestrictedWorldGuard restrictedWorldGuard;
	private final Provider<TradeCloudService> tradeCloudProvider;
	private final Provider<CreditAttestQueue> attestQueueProvider;
	private final CloudCollectionPager collectionPager;
	private final CloudCollectionSyncService collectionSync;
	private final HiscoresSettleService hiscoresSettle;
	private final CloudProfileConsentService profileConsent;

	private final AtomicReference<CloudConnectionState> connectionState =
		new AtomicReference<>(CloudConnectionState.DISCONNECTED);
	private final AtomicReference<String> statusMessage = new AtomicReference<>("Disconnected");
	private final AtomicReference<Runnable> statusListener = new AtomicReference<>(null);
	private final AtomicBoolean hiscoresSettledThisLogin = new AtomicBoolean(false);
	private final AtomicBoolean hiscoresRetryScheduled = new AtomicBoolean(false);
	private final AtomicBoolean accountBanned = new AtomicBoolean(false);
	private final AtomicBoolean accountQuarantined = new AtomicBoolean(false);
	/** Server-suggested reconnect delay ms (0 = unset); taken by the coordinator once. */
	private final AtomicLong suggestedReconnectDelayMs = new AtomicLong(0L);
	private final List<Runnable> accountLockCleanups = new CopyOnWriteArrayList<>();

	public static final String ACCOUNT_BANNED_STATUS =
		"Your account has been banned. Check the account panel for more information.";
	public static final String ACCOUNT_QUARANTINED_STATUS =
		"Your account is quarantined. Check the account panel for more information.";
/**
	 * Wires collaborators, constructs the internal {@link CloudCollectionPager}/{@link CloudCollectionSyncService}/
	 * {@link HiscoresSettleService}/{@link CloudProfileConsentService} helpers, and registers this
	 * service's stale-refresh and account-lock handlers with {@code api}.
	 */
	@Inject
	CloudSessionService(
		Client client,
		CloudApiClient api,
		CloudTokenStore tokens,
		ProfileKeyHasher profileKeyHasher,
		TcgStateService stateService,
		ChatMessageManager chatMessageManager,
		PackCatalogService packCatalogService,
		CardCatalogService cardCatalogService,
		CardImageCacheService cardImageCacheService,
		ActivityConfigService activityConfigService,
		RestrictedWorldGuard restrictedWorldGuard,
		ScheduledExecutorService scheduler,
		Provider<TradeCloudService> tradeCloudProvider,
		Provider<CreditAttestQueue> attestQueueProvider,
		TcgPublicStatsCalculator publicStatsCalculator)
	{
		this.client = client;
		this.api = api;
		this.tokens = tokens;
		this.profileKeyHasher = profileKeyHasher;
		this.stateService = stateService;
		this.chatMessageManager = chatMessageManager;
		this.packCatalogService = packCatalogService;
		this.cardCatalogService = cardCatalogService;
		this.cardImageCacheService = cardImageCacheService;
		this.activityConfigService = activityConfigService;
		this.restrictedWorldGuard = restrictedWorldGuard;
		this.tradeCloudProvider = tradeCloudProvider;
		this.attestQueueProvider = attestQueueProvider;
		this.collectionPager = new CloudCollectionPager(api);
		this.collectionSync = new CloudCollectionSyncService(
			this, api, tokens, stateService, attestQueueProvider,
			publicStatsCalculator, collectionPager);
		this.hiscoresSettle = new HiscoresSettleService(
			client, api, tokens, restrictedWorldGuard, scheduler, chatMessageManager, tradeCloudProvider,
			collectionSync::applySidebarStats, hiscoresSettledThisLogin, hiscoresRetryScheduled,
			this::needsCloudConsent, this::isAccountLocked);
		this.profileConsent = new CloudProfileConsentService(
			this, collectionSync, hiscoresSettle, client, api, tokens, profileKeyHasher, stateService,
			chatMessageManager, packCatalogService, cardCatalogService, activityConfigService);
		api.setStaleRefreshHandler(this::handleStaleRefresh);
		api.setAccountLockHandler(this::noteLockFromApiException);
	}
/** Invoked by {@link CloudApiClient} when a refresh token is rejected as stale: clears login gates and disconnects. */
	private void handleStaleRefresh()
	{
		clearLoginFetchGates();
		setState(CloudConnectionState.DISCONNECTED,
			needsCloudConsent() ? CONSENT_WAITING_STATUS : "Disconnected");
	}
/** Current cloud connection state. */
	public CloudConnectionState getConnectionState()
	{
		return connectionState.get();
	}
/** Human-readable status message associated with the current connection state. */
	public String getStatusMessage()
	{
		return statusMessage.get();
	}
/** Whether the player needs to log into RuneScape before cloud session setup can proceed. */
	public boolean isRunescapeLoginRequired()
	{
		return client.getGameState() != GameState.LOGGED_IN;
	}
/** Whether the connection state is CONNECTED and an access token is present. */
	public boolean isSessionActive()
	{
		return connectionState.get() == CloudConnectionState.CONNECTED && tokens.getAccessToken() != null;
	}
/** Whether the session is active and no gate (consent/lock/restricted world) is blocking traffic. */
	public boolean isReady()
	{
		return isSessionActive() && cloudGatesOpen() && tokens.tokensBoundTo(client.getAccountHash());
	}
/**
	 * Whether credit attests may currently be collected: gates open and cloud tokens are bound to
	 * the live Jagex account hash (prevents enqueue while another account's JWTs are still stored).
	 */
	public boolean canCollectAttests()
	{
		return cloudGatesOpen() && tokens.tokensBoundTo(client.getAccountHash());
	}
/** True unless cloud consent is pending, the account is locked, or the world is restricted. */
	private boolean cloudGatesOpen()
	{
		return !needsCloudConsent() && !isAccountLocked()
			&& !isRestrictedWorld();
	}
/**
	 * Updates the status message to indicate an offline reconnect was scheduled, if a reconnect is
	 * applicable and the current state is ERROR/DISCONNECTED. No-op otherwise.
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
/** Takes and clears any stashed reconnect delay ms; returns 0 when none was set. */
	public long takeSuggestedReconnectDelayMs()
	{
		return suggestedReconnectDelayMs.getAndSet(0L);
	}
/** Whether the current world type is one where cloud credits are disabled. */
	public boolean isRestrictedWorld()
	{
		return restrictedWorldGuard.isRestricted();
	}
/** Whether the account is currently flagged banned. */
	public boolean isAccountBanned()
	{
		return accountBanned.get();
	}
/** Whether the account is currently flagged quarantined. */
	public boolean isAccountQuarantined()
	{
		return accountQuarantined.get();
	}
/** Whether the account is banned or quarantined. */
	public boolean isAccountLocked()
	{
		return isAccountBanned() || isAccountQuarantined();
	}
/** Whether the account panel may be opened: requires a token, consent, an unrestricted world, and (active or locked) session. */
	public boolean canOpenAccountPanel()
	{
		if (tokens.getAccessToken() == null || needsCloudConsent() || isRestrictedWorld())
		{
			return false;
		}
		return isSessionActive() || isAccountLocked();
	}
/** Stops hiscores/activity polling and moves the connection to DISCONNECTED with a restricted-world message. */
	public void enterRestrictedWorld()
	{
		activityConfigService.stopQuietPoll();
		String detail = restrictedWorldGuard.describeBlockedTypes();
		String message = detail.isEmpty()
			? RestrictedWorldGuard.STATUS_MESSAGE
			: RestrictedWorldGuard.STATUS_MESSAGE + " (" + detail + ")";
		setState(CloudConnectionState.DISCONNECTED, message);
	}
/** Flags the account as banned and pauses all cloud traffic. */
	public void enterAccountBanned()
	{
		enterAccountLock(accountBanned, ACCOUNT_BANNED_STATUS, "banned");
	}
/** Flags the account as quarantined and pauses all cloud traffic, unless already banned. */
	public void enterAccountQuarantined()
	{
		if (isAccountBanned())
		{
			return;
		}
		enterAccountLock(accountQuarantined, ACCOUNT_QUARANTINED_STATUS, "quarantined");
	}
/** Sets {@code flag}, pauses cloud traffic, updates status, and logs once on first transition into the lock. */
	private void enterAccountLock(AtomicBoolean flag, String statusMessage, String kind)
	{
		boolean already = flag.getAndSet(true);
		pauseCloudTrafficOnLock();
		setState(CloudConnectionState.DISCONNECTED, statusMessage);
		if (!already)
		{
			log.warn("Account {}; cloud traffic stopped until logout", kind);
		}
	}
/** Persists {@code status} and, if it's "banned"/"quarantined", enters the corresponding lock. No-op if blank. */
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
/** Registers a callback to run when the account transitions into a locked (banned/quarantined) state. */
	public void registerAccountLockCleanup(Runnable cleanup)
	{
		if (cleanup != null)
		{
			accountLockCleanups.add(cleanup);
		}
	}
/**
	 * Stops and discards pending attests, runs registered lock cleanups (catching and logging any
	 * failure so one bad cleanup doesn't block the rest), and stops trade sync, activity polling,
	 * hiscores gating, and the pack catalog.
	 */
	private void pauseCloudTrafficOnLock()
	{
		CreditAttestQueue attestQueue = attestQueueProvider.get();
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
/** Applies banned/quarantined flags read from an attest response, if present. No-op if {@code response} is null. */
	public void noteAttestBanFlags(JsonObject response)
	{
		if (response == null)
		{
			return;
		}
		applyAccountLockFlags(
			JsonObjects.readBoolean(response, "banned"),
			JsonObjects.readBoolean(response, "quarantined"));
	}
/** Applies banned/quarantined flags carried on a {@link CloudApiException}, if present. No-op if null. */
	public void noteLockFromApiException(CloudApiException ex)
	{
		if (ex == null)
		{
			return;
		}
		applyAccountLockFlags(ex.isAccountBanned(), ex.isAccountQuarantined());
	}
/** Enters the banned lock if {@code banned}, else the quarantined lock if {@code quarantined}. */
	private void applyAccountLockFlags(boolean banned, boolean quarantined)
	{
		if (banned)
		{
			enterAccountBanned();
			return;
		}
		if (quarantined)
		{
			enterAccountQuarantined();
		}
	}
/** Whether pending credit attests may be flushed to the server right now. */
	public boolean canAttestFlush()
	{
		if (tokens.getAccessToken() == null || needsCloudConsent() || isAccountLocked())
		{
			return false;
		}
		long live = client.getAccountHash();
		if (live != -1L)
		{
			return tokens.tokensBoundTo(live);
		}
		// Logout teardown: hash already cleared; allow flush if tokens remain bound to an account.
		return tokens.getBoundAccountHash() != -1L;
	}
/** Whether this profile still needs to complete the cloud consent/migration flow. */
	public boolean needsCloudConsent()
	{
		return !tokens.isMigrated();
	}
/** Replaces the connection-status change listener (single slot; pass {@code null} to clear). */
	public void setStatusListener(Runnable listener)
	{
		statusListener.set(listener);
	}
/** Whether the status message indicates we're blocked waiting for account hash or display name. */
	public boolean isWaitingForGameIdentity()
	{
		String message = statusMessage.get();
		return WAITING_FOR_DISPLAY_NAME.equals(message) || WAITING_FOR_ACCOUNT.equals(message);
	}
/**
	 * Synchronously drives the cloud session state machine to completion: validates preconditions
	 * (not locked, not restricted world, logged in, has account hash, consent granted, has display
	 * name/profile key), refreshes or pairs credentials, adopts server migration if needed, syncs
	 * collection state, settles hiscores, and refreshes catalogs — moving through CONNECTING to
	 * CONNECTED, or to ERROR/DISCONNECTED with an explanatory status message on failure. Blocking:
	 * issues synchronous HTTP calls; callers must invoke off the client/EDT thread. Synchronized so
	 * concurrent callers serialize rather than racing the connection state machine.
	 */
	public synchronized void ensureSession()
	{
		if (isAccountLocked())
		{
			setState(CloudConnectionState.DISCONNECTED,
				isAccountBanned() ? ACCOUNT_BANNED_STATUS : ACCOUNT_QUARANTINED_STATUS);
			return;
		}
		if (isRestrictedWorld())
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
			setState(CloudConnectionState.DISCONNECTED, WAITING_FOR_ACCOUNT);
			return;
		}
		if (needsCloudConsent())
		{
			setState(CloudConnectionState.DISCONNECTED, CONSENT_WAITING_STATUS);
			return;
		}
		if (CloudTokenStore.shouldClearForAccount(tokens.getBoundAccountHash(), tokens.hasRefreshToken(), accountHash))
		{
			log.info("Clearing cloud credentials bound to a different account");
			tokens.clear();
		}
		String displayName = resolveDisplayName();
		boolean needsDisplayName = !tokens.hasRefreshToken();
		if (needsDisplayName && displayNameMissing(displayName))
		{
			setState(CloudConnectionState.DISCONNECTED, WAITING_FOR_DISPLAY_NAME);
			return;
		}
		String profileHash = profileKeyHasher.currentProfileKeyHash();
		if (profileHash == null)
		{
			setState(CloudConnectionState.ERROR, "No RuneLite profile key");
			return;
		}

		// Hop reconnects keep an active session; only settle after a real login/disconnect.
		boolean shouldSettle = !isSessionActive();
		setState(CloudConnectionState.CONNECTING, "Connecting…");
		try
		{
			api.getHealth();
			if (tokens.hasRefreshToken())
			{
				try
				{
					api.applyTokenResponse(api.refresh(tokens.getRefreshToken(), profileHash), accountHash);
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
				if (displayNameMissing(displayName))
				{
					setState(CloudConnectionState.DISCONNECTED, WAITING_FOR_DISPLAY_NAME);
					return;
				}
				pairSession(displayName, profileHash, accountHash);
			}

			profileConsent.adoptServerMigrationIfNeeded();
			collectionSync.refreshLocalCacheFromCloud();
			if (isAccountLocked())
			{
				return;
			}
			if (shouldSettle)
			{
				hiscoresSettle.settleAfterCloudLogin();
			}
			if (isAccountLocked())
			{
				return;
			}
			suggestedReconnectDelayMs.set(0L);
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
			Long sec = ex.getRetryAfterSec();
			if (sec != null && sec > 0L)
			{
				suggestedReconnectDelayMs.set(sec * 1000L);
			}
			setState(CloudConnectionState.ERROR, ex.getMessage());
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				"Cloud: " + ex.getMessage());
		}
		catch (Exception ex)
		{
			log.warn("Cloud session failed", ex);
			setState(CloudConnectionState.ERROR, "Cloud unreachable");
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				"Cloud unreachable");
		}
	}
/** Sanitized local player display name, or {@code null} if the player/name isn't available yet. */
	String resolveDisplayName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return null;
		}
		String name = Text.sanitize(client.getLocalPlayer().getName());
		return name == null || name.isEmpty() ? null : name;
	}
/** Delegates to {@link CloudProfileConsentService#createProfile()}. Blocking; synchronized. */
	public synchronized void createProfile() throws Exception
	{
		profileConsent.createProfile();
	}
/** Deletes on-disk card catalog and image caches left over from before this profile migrated. */
	void deleteObsoleteLocalCaches()
	{
		cardCatalogService.deleteDiskCache();
		cardImageCacheService.deleteObsoleteImageCacheDirs();
	}
/** Clears lock flags, login gates, and cloud-derived local caches, then moves to DISCONNECTED. */
	public void disconnectQuietly()
	{
		accountBanned.set(false);
		accountQuarantined.set(false);
		suggestedReconnectDelayMs.set(0L);
		clearLoginFetchGates();
		stateService.clearCollectionStatsCache();
		stateService.clearCloudGroupKey();
		setState(CloudConnectionState.DISCONNECTED, "Disconnected");
	}
/**
	 * Cancels any in-flight/pending hiscores settle (including delayed retries). Call at the start of
	 * logout/shutdown teardown so settle-hiscores cannot be sent while attests are flushing.
	 */
	public void cancelHiscoresSettle()
	{
		hiscoresSettle.clearGate();
	}
/** Delegates to {@link CloudCollectionSyncService#applySidebarStats(JsonObject)}. */
	public void applySidebarStats(JsonObject stats)
	{
		collectionSync.applySidebarStats(stats);
	}
/** Delegates to {@link CloudCollectionSyncService#reconcileCollectionFromInbox(JsonObject)}. */
	public void reconcileCollectionFromInbox(JsonObject stats)
	{
		collectionSync.reconcileCollectionFromInbox(stats);
	}
/** Delegates to {@link CloudCollectionSyncService#refreshCreditsFromServer()}. Blocking. */
	public void refreshCreditsFromServer() throws Exception
	{
		collectionSync.refreshCreditsFromServer();
	}
/**
	 * Delegates to {@link CloudCollectionSyncService#refreshCreditsFromServer(boolean)}. Blocking.
	 * Pass {@code flushFirst=false} when already inside an attest flush to avoid deadlock.
	 */
	public void refreshCreditsFromServer(boolean flushFirst) throws Exception
	{
		collectionSync.refreshCreditsFromServer(flushFirst);
	}
/** Pairs a new session for this profile/account and applies the returned token response. Blocking. */
	void pairSession(String displayName, String profileHash, long accountHash)
		throws CloudApiException, IOException
	{
		JsonObject start = api.pairStart(displayName, profileHash, accountHash);
		api.applyTokenResponse(start, accountHash);
	}
/** Whether the local state has any credits, opened packs, or owned cards worth migrating. */
	boolean hasLocalProgress()
	{
		TcgState local = stateService.getState();
		return local.getEconomyState().getCredits() > 0
			|| local.getEconomyState().getOpenedPacks() > 0
			|| !local.getCollectionState().getOwnedInstances().isEmpty();
	}
/** Updates connection state and status message, then notifies the registered status listener, if any. */
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
/** Resets per-login one-shot gates (catalog fetch, activity polling, hiscores settle) so they re-run on next login. */
	private void clearLoginFetchGates()
	{
		packCatalogService.clear();
		cardCatalogService.resetLoginFetchGate();
		activityConfigService.stopQuietPoll();
		hiscoresSettle.clearGate();
	}
/** Whether {@code displayName} is null or empty. */
	private static boolean displayNameMissing(String displayName)
	{
		return displayName == null || displayName.isEmpty();
	}
}

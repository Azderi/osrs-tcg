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
	private final CloudProfileConsentService profileConsent;

	private final AtomicReference<CloudConnectionState> connectionState =
		new AtomicReference<>(CloudConnectionState.DISCONNECTED);
	private final AtomicReference<String> statusMessage = new AtomicReference<>("Disconnected");
	private final AtomicReference<Runnable> statusListener = new AtomicReference<>(null);
	private final AtomicBoolean hiscoresSettledThisLogin = new AtomicBoolean(false);
	private final AtomicBoolean hiscoresSettleRetryScheduled = new AtomicBoolean(false);
	private final AtomicBoolean forceStatePullOnce = new AtomicBoolean(false);
	private final AtomicBoolean accountBanned = new AtomicBoolean(false);
	private final AtomicBoolean accountQuarantined = new AtomicBoolean(false);
	private final List<Runnable> accountLockCleanups = new CopyOnWriteArrayList<>();

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
			this, api, tokens, stateService, creditAttestQueueProvider,
			publicStatsCalculator, collectionPager, forceStatePullOnce);
		this.hiscoresSettle = new HiscoresSettleService(
			client, api, tokens, restrictedWorldGuard, scheduler, chatMessageManager, tradeCloudProvider,
			collectionSync::applySidebarStats, hiscoresSettledThisLogin, hiscoresSettleRetryScheduled,
			this::needsCloudConsent, this::isAccountLocked);
		this.profileConsent = new CloudProfileConsentService(
			this, collectionSync, client, api, tokens, profileKeyHasher, stateService,
			chatMessageManager, packCatalogService, cardCatalogService, activityConfigService);
		api.setStaleRefreshHandler(this::handleStaleRefresh);
		api.setAccountLockHandler(this::noteLockFromApiException);
	}

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

	public boolean isSessionActive()
	{
		return connectionState.get() == CloudConnectionState.CONNECTED && tokens.getAccessToken() != null;
	}

	public boolean isReady()
	{
		return isSessionActive()
			&& !needsCloudConsent()
			&& !isRestrictedWorld()
			&& !isAccountLocked()
			&& !stateService.isDebugLogging();
	}

	public boolean canCollectAttests()
	{
		return !needsCloudConsent()
			&& !isAccountLocked()
			&& !isRestrictedWorld()
			&& !stateService.isDebugLogging();
	}

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

	public boolean isAccountBanned()
	{
		return accountBanned.get();
	}

	public boolean isAccountQuarantined()
	{
		return accountQuarantined.get();
	}

	public boolean isAccountLocked()
	{
		return isAccountBanned() || isAccountQuarantined();
	}

	public boolean canOpenAccountPanel()
	{
		if (tokens.getAccessToken() == null || needsCloudConsent() || isRestrictedWorld())
		{
			return false;
		}
		return isSessionActive() || isAccountLocked();
	}

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

	public void enterAccountBanned()
	{
		enterAccountLock(accountBanned, ACCOUNT_BANNED_STATUS, "banned");
	}

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

	public boolean canAttestFlush()
	{
		return tokens.getAccessToken() != null && !needsCloudConsent() && !isAccountLocked();
	}

	/** True until Create profile is accepted; no cloud HTTP except the consent action itself. */
	public boolean needsCloudConsent()
	{
		return !tokens.isMigrated();
	}

	/** Show Create profile until cloud consent is accepted. */
	public boolean needsProfileCreate()
	{
		return needsCloudConsent();
	}

	private String consentWaitingMessage()
	{
		return "Create a profile";
	}

	public void setStatusListener(Runnable listener)
	{
		statusListener.set(listener);
	}

	public boolean isWaitingForGameIdentity()
	{
		String message = statusMessage.get();
		return "Waiting for display name".equals(message) || "Waiting for account".equals(message);
	}

	public synchronized void ensureSession()
	{
		if (stateService.isDebugLogging())
		{
			setState(CloudConnectionState.DISCONNECTED, "Debug mode");
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
		// No cloud API until the user accepts Create profile.
		if (needsCloudConsent())
		{
			setState(CloudConnectionState.DISCONNECTED, consentWaitingMessage());
			return;
		}
		String displayName = resolveDisplayName();
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

			profileConsent.adoptServerMigrationIfNeeded();
			collectionSync.refreshLocalCacheFromCloud();
			if (isAccountLocked())
			{
				return;
			}
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

	String resolveDisplayName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return null;
		}
		String name = Text.sanitize(client.getLocalPlayer().getName());
		return name == null || name.isEmpty() ? null : name;
	}

	/** Pair/refresh if needed and accept cloud consent. Call off the EDT. */
	public synchronized void createProfile() throws Exception
	{
		profileConsent.createProfile();
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

package com.osrstcg.cloud.session;

import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.TcgState;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.TcgPluginGameMessages;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Provider;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.attest.CreditAttestQueue;

/**
 * {@code /me/state} collection pages, reconcile, and sidebar stats.
 */
@Slf4j
final class CloudCollectionSyncService
{
	private final CloudSessionService session;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final TcgStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final Provider<CreditAttestQueue> creditAttestQueueProvider;
	private final TcgPublicStatsCalculator publicStatsCalculator;
	private final CloudCollectionPager pager;
	private final AtomicBoolean forceStatePullOnce;
	private final AtomicBoolean deferCollectionPullForMigrateImport;

	CloudCollectionSyncService(
		CloudSessionService session,
		CloudApiClient api,
		CloudTokenStore tokens,
		TcgStateService stateService,
		ChatMessageManager chatMessageManager,
		Provider<CreditAttestQueue> creditAttestQueueProvider,
		TcgPublicStatsCalculator publicStatsCalculator,
		CloudCollectionPager pager,
		AtomicBoolean forceStatePullOnce,
		AtomicBoolean deferCollectionPullForMigrateImport)
	{
		this.session = session;
		this.api = api;
		this.tokens = tokens;
		this.stateService = stateService;
		this.chatMessageManager = chatMessageManager;
		this.creditAttestQueueProvider = creditAttestQueueProvider;
		this.publicStatsCalculator = publicStatsCalculator;
		this.pager = pager;
		this.forceStatePullOnce = forceStatePullOnce;
		this.deferCollectionPullForMigrateImport = deferCollectionPullForMigrateImport;
	}

	void applySidebarStats(JsonObject stats)
	{
		if (stats == null)
		{
			return;
		}
		boolean hasEconomy = stats.has("credits") || stats.has("openedPacks") || stats.has("totalCreditsGained");
		if (hasEconomy)
		{
			long credits = stats.has("credits")
				? stats.get("credits").getAsLong()
				: stateService.getAuthoritativeCredits();
			int openedPacks = stats.has("openedPacks")
				? stats.get("openedPacks").getAsInt()
				: (int) stateService.getState().getEconomyState().getOpenedPacks();
			long totalGained = stats.has("totalCreditsGained")
				? stats.get("totalCreditsGained").getAsLong()
				: stateService.getState().getTotalCreditsGained();
			stateService.replaceCloudEconomyCache(credits, openedPacks, totalGained);
		}
		if (CloudSidebarCollectionStats.hasCollectionFields(stats))
		{
			stateService.replaceCloudCollectionStatsCache(CloudSidebarCollectionStats.fromStatsJson(stats));
		}
		if (stats.has("status") && !stats.get("status").isJsonNull())
		{
			session.applyAccountStatus(stats.get("status").getAsString());
		}
	}

	void maybeReconcileCollectionFromInbox(JsonObject stats)
	{
		if (stats == null || session.needsCloudConsent())
		{
			return;
		}
		try
		{
			CloudPlayerStateParser.SyncMarkers serverMarkers = CloudPlayerStateParser.readSyncMarkers(stats);
			long localRevision = stateService.getState().getCloudRevision();
			if (CloudSidebarCollectionStats.hasCollectionFields(stats))
			{
				CloudSidebarCollectionStats server = CloudSidebarCollectionStats.fromStatsJson(stats);
				CloudSidebarCollectionStats local = publicStatsCalculator.computeLocalSidebarStats();
				if (!CloudSidebarCollectionStats.countsAgree(server, local))
				{
					String localCollHash = stateService.getCloudCollectionHash();
					String serverCollHash = serverMarkers.collectionHash;
					boolean collectionChanged = (!serverCollHash.isEmpty()
							&& !serverCollHash.equalsIgnoreCase(localCollHash))
						|| (localCollHash.isEmpty() && !serverCollHash.isEmpty())
						|| (serverCollHash.isEmpty() && localRevision < serverMarkers.revision);
					if (collectionChanged)
					{
						log.info("Collection overview mismatch (server unique={} local unique={}) - pulling /me/cards",
							server.getUniqueOwned(), local.getUniqueOwned());
						forceStatePullOnce.set(true);
					}
					else
					{
						log.debug("Collection overview mismatch with unchanged collection hash "
							+ "(server unique={} local unique={}) - skipping forced /me/cards",
							server.getUniqueOwned(), local.getUniqueOwned());
					}
				}
			}
			reconcileCollectionWithCloud(stats);
		}
		catch (Exception e)
		{
			log.debug("Collection reconcile from inbox failed", e);
		}
	}

	void refreshCreditsFromServer() throws Exception
	{
		if (tokens.getAccessToken() == null || session.needsCloudConsent() || session.isAccountLocked())
		{
			return;
		}
		try
		{
			creditAttestQueueProvider.get().flushBlocking();
		}
		catch (Exception ex)
		{
			log.debug("Attest flush before credit refresh failed", ex);
		}
		JsonObject stats = api.getStats();
		applySidebarStats(stats);
		stateService.clearOptimisticCredits();
	}

	boolean forceRefreshCollectionState()
	{
		if (!session.isReady())
		{
			return false;
		}
		try
		{
			forceStatePullOnce.set(true);
			JsonObject stats = api.getStats();
			applySidebarStats(stats);
			reconcileCollectionWithCloud(stats);
			return true;
		}
		catch (Exception e)
		{
			log.warn("Forced collection refresh failed", e);
			return false;
		}
	}

	void refreshLocalCacheFromCloud() throws Exception
	{
		JsonObject stats = api.getStats();
		applySidebarStats(stats);
		reconcileCollectionWithCloud(stats);
	}

	void reconcileCollectionWithCloud(JsonObject stats) throws Exception
	{
		if (session.needsCloudConsent())
		{
			return;
		}

		CloudPlayerStateParser.SyncMarkers server = CloudPlayerStateParser.readSyncMarkers(stats);
		TcgState local = stateService.getState();
		long localRevision = local.getCloudRevision();
		String localHash = local.getCloudStateHash() == null ? "" : local.getCloudStateHash();
		String localCollHash = stateService.getCloudCollectionHash();
		String serverCollHash = server.collectionHash;
		boolean force = forceStatePullOnce.compareAndSet(true, false);
		boolean collectionChanged = force
			|| (!serverCollHash.isEmpty() && !serverCollHash.equalsIgnoreCase(localCollHash))
			|| (localCollHash.isEmpty() && !serverCollHash.isEmpty())
			|| (serverCollHash.isEmpty() && server.revision > localRevision);

		if (!collectionChanged)
		{
			if (server.revision > localRevision
				|| (!server.stateHash.isEmpty() && !server.stateHash.equalsIgnoreCase(localHash)))
			{
				stateService.applyCloudSyncMarkers(server.revision, server.stateHash);
			}
			return;
		}

		String reason = force ? "forced"
			: (localCollHash.isEmpty() && !serverCollHash.isEmpty()) ? "missing local collection hash"
			: (serverCollHash.isEmpty() && server.revision > localRevision) ? "legacy revision behind"
			: "collection hash mismatch";
		debugCollectionSync("Requesting collection sync from server (" + reason
			+ "; local collHash=" + localCollHash + ", server collHash=" + serverCollHash + ")");

		JsonObject stateJson = api.getState();
		CloudPlayerStateParser.ParsedCloudPlayerState parsed = pager.loadCloudPlayerStateWithCards(stateJson);
		if (!parsed.migrated)
		{
			if (deferCollectionPullForMigrateImport.get())
			{
				log.info("Cloud migrate import still pending; skipping collection pull");
				debugCollectionSync("Collection sync skipped - migrate import pending");
				return;
			}
			if (!tokens.isMigrated())
			{
				log.info("Cloud /me/state reports account not migrated yet; skipping collection pull");
				debugCollectionSync("Collection sync skipped - account not migrated yet");
				return;
			}
		}
		else
		{
			tokens.setMigrated(true);
			deferCollectionPullForMigrateImport.set(false);
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
		if (parsed.accountStatus != null && !parsed.accountStatus.isBlank())
		{
			session.applyAccountStatus(parsed.accountStatus);
		}
		log.info("Synced collection from cloud (revision={}, cards={}, migratedAtPresent={})",
			parsed.revision, parsed.cards.size(), parsed.migrated);
		debugCollectionSync("Collection synced from server (revision=" + parsed.revision
			+ ", " + parsed.cards.size() + " cards)");
	}

	CloudPlayerStateParser.ParsedCloudPlayerState loadCloudPlayerStateWithCards(JsonObject stateJson) throws Exception
	{
		return pager.loadCloudPlayerStateWithCards(stateJson);
	}

	private void debugCollectionSync(String message)
	{
		if (!stateService.isDebugChatEnabled() || message == null || message.isBlank())
		{
			return;
		}
		log.info("[TCG DEBUG] {}", message);
		TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, message);
	}
}

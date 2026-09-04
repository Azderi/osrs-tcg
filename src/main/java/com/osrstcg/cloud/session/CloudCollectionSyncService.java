package com.osrstcg.cloud.session;

import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.TcgState;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.state.TcgStateService;
import com.google.gson.JsonObject;
import javax.inject.Provider;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.attest.CreditAttestQueue;
/**
 * Pulls cloud economy/collection state and reconciles it into local {@link TcgStateService}.
 * Compares local vs server revisions and collection hashes to decide whether a full {@code /me/cards}
 * pull is needed. Blocking: the {@code refresh*}/{@code reconcile*} methods issue synchronous HTTP
 * calls and must not run on the client/EDT thread.
 */
@Slf4j
final class CloudCollectionSyncService
{
	private final CloudSessionService session;
	private final CloudApiClient api;
	private final CloudTokenStore tokens;
	private final TcgStateService stateService;
	private final Provider<CreditAttestQueue> attestQueueProvider;
	private final TcgPublicStatsCalculator publicStatsCalculator;
	private final CloudCollectionPager pager;
/** Wires collaborators; no side effects. */
	CloudCollectionSyncService(
		CloudSessionService session,
		CloudApiClient api,
		CloudTokenStore tokens,
		TcgStateService stateService,
		Provider<CreditAttestQueue> attestQueueProvider,
		TcgPublicStatsCalculator publicStatsCalculator,
		CloudCollectionPager pager)
	{
		this.session = session;
		this.api = api;
		this.tokens = tokens;
		this.stateService = stateService;
		this.attestQueueProvider = attestQueueProvider;
		this.publicStatsCalculator = publicStatsCalculator;
		this.pager = pager;
	}
/**
	 * Updates cached economy (credits/opened packs/total gained) and collection sidebar stats from a
	 * {@code stats}-shaped response, and applies any account status it carries. No-op if {@code stats}
	 * is null.
	 */
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
			stateService.replaceCollectionStatsCache(CloudSidebarCollectionStats.fromStatsJson(stats));
		}
		if (stats.has("status") && !stats.get("status").isJsonNull())
		{
			session.applyAccountStatus(stats.get("status").getAsString());
		}
	}
/**
	 * Reacts to a push/inbox stats update: logs (does not itself resolve) a mismatch between server
	 * and locally-computed sidebar counts, then delegates to {@link #reconcileCollectionWithCloud}
	 * to pull a fresh collection if needed. No-op while cloud consent is pending, or if {@code stats}
	 * is null. Reconcile failures are swallowed and logged at debug level.
	 */
	void reconcileCollectionFromInbox(JsonObject stats)
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
					boolean collectionChanged =
						(!serverCollHash.isEmpty() && !serverCollHash.equalsIgnoreCase(localCollHash))
						|| (serverCollHash.isEmpty() && localRevision < serverMarkers.revision);
					if (collectionChanged)
					{
						log.info("Collection overview mismatch (server unique={} local unique={}) - pulling /me/cards",
							server.getUniqueOwned(), local.getUniqueOwned());
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
/**
	 * Flushes any pending credit attests, then re-fetches and applies server stats, clearing the
	 * local optimistic credit adjustment. No-op if there's no access token, consent is pending, or
	 * the account is locked.
	 */
	void refreshCreditsFromServer() throws Exception
	{
		refreshCreditsFromServer(true);
	}
/**
	 * Re-fetches and applies server stats ({@code GET /me/stats}), clearing local optimistic credits.
	 * When {@code flushFirst} is true, flushes pending attests first. Use {@code flushFirst=false}
	 * when already inside an attest flush (avoids re-entering the flush gate).
	 */
	void refreshCreditsFromServer(boolean flushFirst) throws Exception
	{
		if (tokens.getAccessToken() == null || session.needsCloudConsent() || session.isAccountLocked())
		{
			return;
		}
		if (flushFirst)
		{
			try
			{
				attestQueueProvider.get().flushBlocking();
			}
			catch (Exception ex)
			{
				log.debug("Attest flush before credit refresh failed", ex);
			}
		}
		JsonObject stats = api.getStats();
		applySidebarStats(stats);
		stateService.clearOptimisticCredits();
	}
/** Fetches server stats, applies them, and reconciles the local collection against the cloud copy. */
	void refreshLocalCacheFromCloud() throws Exception
	{
		JsonObject stats = api.getStats();
		applySidebarStats(stats);
		reconcileCollectionWithCloud(stats);
	}
/**
	 * Compares local sync markers against {@code stats} and, if the collection hash differs (or the
	 * legacy revision is behind with no hash to compare), pulls the full player state and cards from
	 * {@code /me/state} via {@link CloudCollectionPager} and replaces local collection/economy state.
	 * If unchanged but the revision/hash advanced, only updates the local sync markers. No-op while
	 * cloud consent is pending.
	 */
	void reconcileCollectionWithCloud(JsonObject stats) throws Exception
	{
		if (session.needsCloudConsent())
		{
			return;
		}

		CloudPlayerStateParser.SyncMarkers server = CloudPlayerStateParser.readSyncMarkers(stats);
		TcgState local = stateService.getState();
		long localRevision = local.getCloudRevision();
		String localHash = local.getCloudStateHash();
		String localCollHash = stateService.getCloudCollectionHash();
		String serverCollHash = server.collectionHash;
		boolean collectionChanged = (!serverCollHash.isEmpty() && !serverCollHash.equalsIgnoreCase(localCollHash))
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

		String reason = (serverCollHash.isEmpty() && server.revision > localRevision) ? "legacy revision behind"
			: "collection hash mismatch";
		log.info("Requesting collection sync from server ({}; local collHash={}, server collHash={})",
			reason, localCollHash, serverCollHash);

		JsonObject stateJson = api.getState();
		CloudPlayerStateParser.ParsedCloudPlayerState parsed = pager.loadCloudPlayerStateWithCards(stateJson);
		if (!parsed.migrated)
		{
			if (!tokens.isMigrated())
			{
				log.info("Cloud /me/state reports account not migrated yet; skipping collection pull");
				return;
			}
		}
		else
		{
			tokens.setMigrated(true);
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
	}
/** Delegates to {@link CloudCollectionPager#loadCloudPlayerStateWithCards}. */
	CloudPlayerStateParser.ParsedCloudPlayerState loadCloudPlayerStateWithCards(JsonObject stateJson) throws Exception
	{
		return pager.loadCloudPlayerStateWithCards(stateJson);
	}
}

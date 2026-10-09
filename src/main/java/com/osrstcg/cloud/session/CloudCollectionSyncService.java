package com.osrstcg.cloud.session;

import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.CollectionState;
import com.osrstcg.state.TcgState;
import com.osrstcg.interop.TcgPublicStatsCalculator;
import com.osrstcg.state.TcgStateService;
import com.google.gson.JsonObject;
import javax.inject.Provider;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.cloud.attest.CreditAttestQueue;
/**
 * Reconciles cloud collection into {@link TcgStateService}. Prefers collection/changes ops;
 * full /me/cards pull on needsFullReload. Blocking — not for client/EDT thread.
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
			Double creditsNum = JsonObjects.readNumber(stats, "credits");
			long credits = creditsNum == null
				? stateService.getAuthoritativeCredits()
				: Math.round(creditsNum);
			Double openedNum = JsonObjects.readNumber(stats, "openedPacks");
			int openedPacks = openedNum == null
				? (int) stateService.getState().getEconomyState().getOpenedPacks()
				: (int) Math.round(openedNum);
			Double gainedNum = JsonObjects.readNumber(stats, "totalCreditsGained");
			long totalGained = gainedNum == null
				? stateService.getState().getTotalCreditsGained()
				: Math.round(gainedNum);
			stateService.replaceCloudEconomyCache(credits, openedPacks, totalGained);
		}
		if (CloudSidebarCollectionStats.hasCollectionFields(stats))
		{
			stateService.replaceCollectionStatsCache(CloudSidebarCollectionStats.fromStatsJson(stats));
		}
		String status = JsonObjects.text(stats, "status");
		if (status != null)
		{
			session.applyAccountStatus(status);
		}
	}
/**
	 * Reacts to a push/inbox stats update: logs a mismatch between server and locally-computed
	 * sidebar counts, then reconciles (count mismatch forces a full pull). No-op while cloud
	 * consent is pending, or if {@code stats} is null. Reconcile failures are swallowed and logged
	 * at debug level.
	 */
	void reconcileCollectionFromInbox(JsonObject stats)
	{
		if (stats == null || session.needsCloudConsent())
		{
			return;
		}
		try
		{
			reconcileCollectionWithCloud(stats);
		}
		catch (Exception e)
		{
			log.debug("coll inbox", e);
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
/** Sync via changes ops when membership drifts; full pull on needsFullReload/failure. */
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
		boolean collectionChanged = needsCollectionSync(
			localRevision, localCollHash, server.revision, serverCollHash);

		if (!collectionChanged && sidebarCountsDisagree(stats))
		{
			log.info("coll mismatch full");
			pullFullCollectionFromCloud();
			return;
		}
		if (!collectionChanged)
		{
			if (!serverCollHash.isEmpty() && !serverCollHash.equalsIgnoreCase(localCollHash))
			{
				stateService.adoptCloudCollectionHash(serverCollHash);
			}
			if (server.revision > localRevision
				|| (!server.stateHash.isEmpty() && !server.stateHash.equalsIgnoreCase(localHash)))
			{
				stateService.applyCloudSyncMarkers(server.revision, server.stateHash);
			}
			return;
		}
		log.info("coll sync");
		if (!tryApplyCollectionChanges(localRevision))
		{
			pullFullCollectionFromCloud();
		}
	}

	private boolean sidebarCountsDisagree(JsonObject stats)
	{
		if (!CloudSidebarCollectionStats.hasCollectionFields(stats))
		{
			return false;
		}
		return !CloudSidebarCollectionStats.countsAgree(
			CloudSidebarCollectionStats.fromStatsJson(stats),
			publicStatsCalculator.computeLocalSidebarStats());
	}

	/** True when ops applied and markers updated; false → full-pull. */
	private boolean tryApplyCollectionChanges(long sinceRevision)
	{
		try
		{
			JsonObject changes = api.getCollectionChanges(sinceRevision);
			if (changes == null || JsonObjects.readBoolean(changes, "needsFullReload"))
			{
				return false;
			}
			Double revNum = JsonObjects.readNumber(changes, "revision");
			long revision = revNum == null ? sinceRevision : Math.max(0L, Math.round(revNum));
			long localNow = stateService.getState().getCloudRevision();
			if (!isChangesRevisionAcceptable(localNow, revision))
			{
				return false;
			}
			if (!CloudCollectionOpsApplier.applyOps(changes.get("ops"), stateService)
				|| !isChangesRevisionAcceptable(stateService.getState().getCloudRevision(), revision))
			{
				return false;
			}
			stateService.clearTempOwnedInstances();
			String stateHash = JsonObjects.text(changes, "stateHash");
			stateService.applyCloudSyncMarkers(revision, stateHash == null ? "" : stateHash);
			String collectionHash = JsonObjects.text(changes, "collectionHash");
			if (collectionHash != null && !collectionHash.isBlank())
			{
				stateService.adoptCloudCollectionHash(collectionHash);
			}
			if (changes.has("stats") && changes.get("stats").isJsonObject())
			{
				applySidebarStats(changes.getAsJsonObject("stats"));
			}
			return true;
		}
		catch (Exception e)
		{
			log.debug("coll changes", e);
			return false;
		}
	}

	private void pullFullCollectionFromCloud() throws Exception
	{
		CloudPlayerStateParser.ParsedCloudPlayerState parsed =
			pager.loadCloudPlayerStateWithCards(api.getState());
		if (parsed.migrated)
		{
			tokens.setMigrated(true);
		}
		stateService.replaceCloudGroupKey(parsed.groupKey);
		stateService.replaceFromCloudState(
			CollectionState.copyOf(parsed.cards),
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
		log.info("coll full {}", parsed.revision);
	}
/** True when collection should sync (ops or full pull). Equal rev skips; else hash or legacy rev. */
	static boolean needsCollectionSync(
		long localRevision,
		String localCollectionHash,
		long serverRevision,
		String serverCollectionHash)
	{
		if (localRevision == serverRevision)
		{
			return false;
		}
		String localHash = localCollectionHash == null ? "" : localCollectionHash.trim();
		String serverHash = serverCollectionHash == null ? "" : serverCollectionHash.trim();
		if (!serverHash.isEmpty())
		{
			return !serverHash.equalsIgnoreCase(localHash);
		}
		return serverRevision > localRevision;
	}

	/** False when changes rev is strictly behind local (pack-open race). */
	static boolean isChangesRevisionAcceptable(long localRevision, long changesRevision)
	{
		return changesRevision >= localRevision;
	}
}

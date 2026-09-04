package com.osrstcg.cloud.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.state.PackOpenResult;
import com.osrstcg.catalog.CollectionSetCompletionUtil;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.party.TcgPartyAnnouncer;
import com.osrstcg.state.TcgStateService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.util.Text;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.CloudResponseSync;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.catalog.PackPullParser;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.trade.TradeCloudService;
/**
 * Buys a booster pack through the cloud API and applies the resulting pulls, credits and ranks to local state.
 * {@link #buyAndOpenPack(BoosterPackDefinition)} makes a blocking network call and updates shared
 * {@link TcgStateService} state, so callers must invoke it off the client thread and marshal any UI work
 * back onto the client thread themselves.
 */
@Slf4j
@Singleton
public final class CloudPackService
{
	private final CloudApiClient api;
	private final CloudSessionService session;
	private final CreditAttestQueue attestQueue;
	private final TradeCloudService tradeCloud;
	private final PackCatalogService packCatalog;
	private final TcgStateService stateService;
	private final CardDatabase cardDatabase;
	private final Client client;
	private final TcgPartyAnnouncer partyAnnouncer;
/** Wires cloud/session/state collaborators used to buy and resolve a pack open. */
	@Inject
	CloudPackService(
		CloudApiClient api,
		CloudSessionService session,
		CreditAttestQueue attestQueue,
		TradeCloudService tradeCloud,
		PackCatalogService packCatalog,
		TcgStateService stateService,
		CardDatabase cardDatabase,
		Client client,
		TcgPartyAnnouncer partyAnnouncer)
	{
		this.api = api;
		this.session = session;
		this.attestQueue = attestQueue;
		this.tradeCloud = tradeCloud;
		this.packCatalog = packCatalog;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.client = client;
		this.partyAnnouncer = partyAnnouncer;
	}
/**
	 * Buys and opens {@code booster} via the cloud API. Makes a blocking network call - run off the client
	 * thread. Never throws; failures are reported through the returned {@link PackOpenResult}.
	 */
	public PackOpenResult buyAndOpenPack(BoosterPackDefinition booster)
	{
		return buyAndOpenPack(booster, true);
	}
/**
	 * Core buy-and-open flow: validates session/credits/catalog, calls the open-pack endpoint, then updates
	 * owned cards, credits, sidebar ranks and collection stats from the response.
	 *
	 * @param allowCatalogRetry whether a {@code catalog_mismatch} error should trigger one catalog refresh
	 *                          and a single retry with the refreshed pack definition
	 */
	private PackOpenResult buyAndOpenPack(BoosterPackDefinition booster, boolean allowCatalogRetry)
	{
		long creditsBefore = stateService.getCredits();
		if (booster == null)
		{
			return PackOpenResult.failed("No booster pack selected.", creditsBefore, 0);
		}
		if (!session.isReady())
		{
			String reason = session.needsCloudConsent()
				? "Create a profile before opening packs."
				: "Cloud offline - cannot open packs.";
			return PackOpenResult.failed(reason, creditsBefore, booster.getPrice());
		}

		try
		{
			BoosterPackDefinition priced = packCatalog.getCache().get(booster.getId()).orElse(booster);
			int price = priced.getPrice();

			if (stateService.getAuthoritativeCredits() < price)
			{
				attestQueue.flushBlocking();
			}

			long displayCredits = stateService.getCredits();
			if (displayCredits < price)
			{
				refreshCreditsQuietly();
				displayCredits = stateService.getCredits();
				if (displayCredits < price)
				{
					return PackOpenResult.failed(
						"Not enough credits (need " + price + ", have " + displayCredits + ").",
						displayCredits,
						price);
				}
			}

			String catalogVersion = packCatalog.requireCatalogVersion();
			if (catalogVersion == null || catalogVersion.isEmpty())
			{
				return PackOpenResult.failed("Missing catalog version from server.", creditsBefore, price);
			}

			String packId = priced.getId() == null ? "" : priced.getId().trim();
			if (packId.isEmpty())
			{
				return PackOpenResult.failed("Missing pack id.", creditsBefore, price);
			}

			JsonObject body = new JsonObject();
			body.addProperty("packId", packId);
			body.addProperty("clientRequestId", UUID.randomUUID().toString());
			body.addProperty("catalogVersion", catalogVersion);
			body.addProperty("accountHash", Long.toString(client.getAccountHash()));

			Map<CardCollectionKey, Integer> ownedBefore;
			synchronized (stateService)
			{
				ownedBefore = new HashMap<>(stateService.getState().getCollectionState().getOwnedCardsExcludingBeta());
			}

			JsonObject response = api.openPack(body);
			Double creditsNum = JsonObjects.readNumber(response, "credits");
			long creditsAfter = creditsNum == null
				? stateService.getAuthoritativeCredits()
				: Math.round(creditsNum);
			Double gainedNum = JsonObjects.readNumber(response, "totalCreditsGained");
			long totalGained = gainedNum == null
				? stateService.getState().getTotalCreditsGained()
				: Math.round(gainedNum);

			List<PackCardResult> pulls = new ArrayList<>();
			List<OwnedCardInstance> newInstances = new ArrayList<>();
			String localPulledBy = client.getLocalPlayer() == null
				? ""
				: Text.sanitize(client.getLocalPlayer().getName());
			long now = System.currentTimeMillis();
			if (response.has("cards") && response.get("cards").isJsonArray())
			{
				for (var el : response.getAsJsonArray("cards"))
				{
					if (!el.isJsonObject())
					{
						continue;
					}
					PackCardResult pull = PackPullParser.parseCard(el.getAsJsonObject());
					if (pull == null || pull.getCardName().isBlank())
					{
						continue;
					}
					String pulledBy = pull.getPulledBy() == null || pull.getPulledBy().isBlank()
						? localPulledBy
						: pull.getPulledBy().trim();
					long pulledAt = pull.getPulledAtEpochMs() == null || pull.getPulledAtEpochMs() <= 0L
						? now
						: pull.getPulledAtEpochMs();
					PackCardResult normalized = pull.withProvenance(pulledBy, pulledAt);
					newInstances.add(new OwnedCardInstance(
						normalized.getInstanceId(),
						normalized.getCardName(),
						normalized.isFoil(),
						pulledBy,
						pulledAt));
					pulls.add(normalized);
				}
			}
			stateService.addOwnedCardInstances(newInstances);

			int cardsPerPack = Math.max(1, packCatalog.getCache().getPackSize());
			int openedPacks = (int) stateService.getState().getEconomyState().getOpenedPacks()
				+ (pulls.size() / cardsPerPack);

			stateService.replaceCloudEconomyCache(creditsAfter, openedPacks, totalGained);
			absorbRanksFromPackOpen(response);
			CloudSidebarCollectionStats optimistic = CloudSidebarCollectionStats.withOptimisticPackPulls(
				stateService.getCloudCollectionStats(), ownedBefore, pulls);
			if (optimistic != null)
			{
				stateService.replaceCollectionStatsCache(optimistic);
			}
			CloudResponseSync.applyRevision(response, stateService, tradeCloud);
			tradeCloud.requestForcedRefresh();

			Map<CardCollectionKey, Integer> ownedAfter;
			synchronized (stateService)
			{
				ownedAfter = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
			}
			List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(cardDatabase.getCards());
			for (String category : CollectionSetCompletionUtil.newlyCompletedCategories(
				ownedBefore, ownedAfter, rollPool))
			{
				partyAnnouncer.announceSetComplete(category);
			}

			boolean apex = JsonObjects.readBoolean(response, "apex");
			String displayName = priced.getName() == null ? booster.getName() : priced.getName();
			return PackOpenResult.succeeded(
				"Pack opened.",
				creditsBefore,
				creditsAfter,
				price,
				pulls,
				displayName,
				packId,
				apex);
		}
		catch (CloudApiException ex)
		{
			if (allowCatalogRetry && ex.isCatalogMismatch())
			{
				log.info("Pack catalog mismatch - refetching once then retrying open");
				try
				{
					packCatalog.refreshAfterCatalogMismatch().join();
				}
				catch (Exception refreshEx)
				{
					log.warn("catalog_mismatch refetch failed", refreshEx);
				}
				BoosterPackDefinition updated = packCatalog.getCache().get(booster.getId()).orElse(null);
				if (updated == null)
				{
					return PackOpenResult.failed(
						"Pack catalog updated - that pack is no longer available.",
						creditsBefore,
						booster.getPrice());
				}
				return buyAndOpenPack(updated, false);
			}
			log.warn("Pack open failed: {} {}", ex.getCode(), ex.getMessage());
			session.noteLockFromApiException(ex);
			if (ex.isInsufficientCredits())
			{
				applyInsufficientCreditsFix(ex);
			}
			String message = ex.isCatalogMismatch()
				? "Pack catalog updated, try again."
				: ex.getMessage();
			return PackOpenResult.failed(message, stateService.getCredits(), booster.getPrice());
		}
		catch (Exception ex)
		{
			log.warn("Pack open failed", ex);
			return PackOpenResult.failed("Pack open failed: " + ex.getMessage(), creditsBefore, booster.getPrice());
		}
	}
/** Reconciles local credits with the server's reported balance after an insufficient-credits failure. */
	private void applyInsufficientCreditsFix(CloudApiException ex)
	{
		Long serverCredits = ex == null ? null : ex.getServerCredits();
		if (serverCredits != null)
		{
			int openedPacks = (int) stateService.getState().getEconomyState().getOpenedPacks();
			long totalGained = stateService.getState().getTotalCreditsGained();
			stateService.replaceCloudEconomyCache(serverCredits, openedPacks, totalGained);
			stateService.clearOptimisticCredits();
		}
		refreshCreditsQuietly();
	}
/** Refreshes credits from the server, swallowing and logging any failure. */
	private void refreshCreditsQuietly()
	{
		try
		{
			session.refreshCreditsFromServer();
		}
		catch (Exception ex)
		{
			log.warn("Credit refresh after insufficient-credits failed", ex);
		}
	}
/** Applies the optional 6-element {@code ranks} array from a pack-open response to sidebar state, if present and valid. */
	private void absorbRanksFromPackOpen(JsonObject response)
	{
		if (response == null || !response.has("ranks") || !response.get("ranks").isJsonArray())
		{
			return;
		}
		JsonArray arr = response.getAsJsonArray("ranks");
		if (arr.size() != 6)
		{
			return;
		}
		int[] ranks = new int[6];
		for (int i = 0; i < 6; i++)
		{
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber())
			{
				return;
			}
			int n = el.getAsInt();
			if (n < 0)
			{
				return;
			}
			ranks[i] = n;
		}
		stateService.replaceSidebarRanks(ranks);
	}
}

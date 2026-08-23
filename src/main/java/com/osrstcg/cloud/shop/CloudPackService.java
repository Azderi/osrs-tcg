package com.osrstcg.cloud.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.catalog.BoosterPackDefinition;
import com.osrstcg.catalog.CardDatabase;
import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.state.CardCollectionKey;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.PackCardResult;
import com.osrstcg.state.PackOpenResult;
import com.osrstcg.catalog.CollectionSetCompletionUtil;
import com.osrstcg.pack.PackSafeModeService;
import com.osrstcg.catalog.RollPoolFilter;
import com.osrstcg.party.TcgPartyAnnouncer;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.Text;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.catalog.PackCatalogService;
import com.osrstcg.cloud.catalog.PackPullParser;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.trade.TradeCloudService;

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
	private final PackSafeModeService packSafeModeService;
	private final TcgPartyAnnouncer partyAnnouncer;
	private final ChatMessageManager chatMessageManager;

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
		PackSafeModeService packSafeModeService,
		TcgPartyAnnouncer partyAnnouncer,
		ChatMessageManager chatMessageManager)
	{
		this.api = api;
		this.session = session;
		this.attestQueue = attestQueue;
		this.tradeCloud = tradeCloud;
		this.packCatalog = packCatalog;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.client = client;
		this.packSafeModeService = packSafeModeService;
		this.partyAnnouncer = partyAnnouncer;
		this.chatMessageManager = chatMessageManager;
	}

	public PackOpenResult buyAndOpenPack(BoosterPackDefinition booster)
	{
		return buyAndOpenPack(booster, true);
	}

	private PackOpenResult buyAndOpenPack(BoosterPackDefinition booster, boolean allowCatalogMismatchRetry)
	{
		long creditsBefore = stateService.getCredits();
		if (booster == null)
		{
			return PackOpenResult.failed("No booster pack selected.", creditsBefore, 0);
		}
		if (!session.isReady())
		{
			String reason = session.isMigrationPending()
				? "Migrate your collection before opening packs."
				: session.needsProfileCreate()
					? "Create a profile before opening packs."
					: "Cloud offline - cannot open packs.";
			return PackOpenResult.failed(reason, creditsBefore, booster.getPrice());
		}
		if (packSafeModeService != null && packSafeModeService.isPackOpeningBlocked())
		{
			String blockMessage = packSafeModeService.packOpeningBlockMessage();
			return PackOpenResult.failed(
				blockMessage == null ? "Cannot open packs right now." : blockMessage,
				creditsBefore,
				booster.getPrice());
		}

		try
		{
			// Prefer live cache price (server-authoritative after login fetch).
			BoosterPackDefinition priced = packCatalog.getCache().get(booster.getId()).orElse(booster);
			int price = priced.getPrice();

			// Flush pending attests only when settled credits cannot cover this pack; otherwise trust
			// optimistic display credits and let the normal attest timer catch up.
			if (stateService.getAuthoritativeCredits() < price)
			{
				attestQueue.flushBlocking();
			}

			long displayCredits = stateService.getCredits();
			if (displayCredits < price)
			{
				refreshCreditsFromServerQuietly();
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
				ownedBefore = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
			}

			String packLabel = priced.getName() == null || priced.getName().isBlank() ? packId : priced.getName().trim();
			debugPackOpen("Sending pack open request (" + packLabel + ", id=" + packId + ")");
			JsonObject response;
			try
			{
				response = api.openPack(body);
			}
			catch (CloudApiException ex)
			{
				debugPackOpen("Pack open reply failed: HTTP " + ex.getStatus()
					+ " (" + ex.getCode() + ") - " + ex.getMessage());
				throw ex;
			}
			catch (Exception ex)
			{
				String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
				debugPackOpen("Pack open reply failed: " + detail);
				throw ex;
			}
			long creditsAfter = response.has("credits")
				? response.get("credits").getAsLong()
				: stateService.getAuthoritativeCredits();
			int openedPacks = response.has("openedPacks")
				? response.get("openedPacks").getAsInt()
				: (int) stateService.getState().getEconomyState().getOpenedPacks();
			long totalGained = response.has("totalCreditsGained")
				? response.get("totalCreditsGained").getAsLong()
				: stateService.getState().getTotalCreditsGained();

			List<PackCardResult> pulls = new ArrayList<>();
			List<com.osrstcg.state.OwnedCardInstance> newInstances = new ArrayList<>();
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
					String source = pull.getSource() == null || pull.getSource().isBlank()
						? "pack"
						: pull.getSource();
					// Same provenance for store + reveal so wear/foil seeds match the website inspect view.
					PackCardResult normalized = pull.withProvenance(pulledBy, pulledAt, source);
					newInstances.add(new com.osrstcg.state.OwnedCardInstance(
						normalized.getInstanceId(),
						normalized.getCardName(),
						normalized.isFoil(),
						pulledBy,
						pulledAt,
						false,
						normalized.getCondition(),
						false,
						source));
					pulls.add(normalized);
				}
			}
			// One master write for the whole pack - avoid N× encode/save hitch mid-reveal.
			stateService.addOwnedCardInstances(newInstances);

			debugPackOpen("Pack open reply received ("
				+ pulls.size() + " cards, credits=" + NumberFormatting.format(creditsAfter) + ")");

			stateService.replaceCloudEconomyCache(creditsAfter, openedPacks, totalGained);
			absorbRanksFromPackOpen(response);
			CloudSidebarCollectionStats optimistic = CloudSidebarCollectionStats.withOptimisticPackPulls(
				stateService.getCloudCollectionStats(), ownedBefore, pulls);
			if (optimistic != null)
			{
				stateService.replaceCloudCollectionStatsCache(optimistic);
			}
			// Collection instances + economy already match this pack-open response. Adopt sync markers
			// and inbox sinceRevision so the forced poll is cheap (statsUnchanged) and does not
			// re-pull /me/state mid-reveal.
			if (response.has("revision") && !response.get("revision").isJsonNull())
			{
				long revision = response.get("revision").getAsLong();
				String stateHash = "";
				if (response.has("stateHash") && !response.get("stateHash").isJsonNull())
				{
					stateHash = response.get("stateHash").getAsString();
				}
				stateService.applyCloudSyncMarkers(revision, stateHash);
				tradeCloud.noteRevision(revision);
			}
			tradeCloud.requestForcedRefresh();

			if (partyAnnouncer != null)
			{
				Map<CardCollectionKey, Integer> ownedAfter;
				synchronized (stateService)
				{
					ownedAfter = new HashMap<>(stateService.getState().getCollectionState().getOwnedCards());
				}
				List<CardDefinition> rollPool = RollPoolFilter.filterRollPool(cardDatabase.getCards());
				for (String category : CollectionSetCompletionUtil.newlyCompletedPrimaryCategories(
					ownedBefore, ownedAfter, rollPool))
				{
					partyAnnouncer.announceCollectionSetComplete(category);
				}
			}

			boolean apex = response.has("apex") && response.get("apex").getAsBoolean();
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
			if (allowCatalogMismatchRetry && ex.isCatalogMismatch())
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
			if (isInsufficientCreditsError(ex))
			{
				applyInsufficientCreditsCorrection(ex);
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

	/**
	 * Drop optimistic credit inflation and sync to server authority after a pack open is refused
	 * for insufficient funds (so the sidebar matches what the server will spend).
	 */
	private void applyInsufficientCreditsCorrection(CloudApiException ex)
	{
		Long serverCredits = ex == null ? null : ex.getServerCredits();
		if (serverCredits != null)
		{
			int openedPacks = (int) stateService.getState().getEconomyState().getOpenedPacks();
			long totalGained = stateService.getState().getTotalCreditsGained();
			stateService.replaceCloudEconomyCache(serverCredits, openedPacks, totalGained);
			stateService.clearOptimisticCredits();
		}
		refreshCreditsFromServerQuietly();
	}

	private void refreshCreditsFromServerQuietly()
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

	private static boolean isInsufficientCreditsError(CloudApiException ex)
	{
		if (ex == null)
		{
			return false;
		}
		String code = ex.getCode() == null ? "" : ex.getCode().trim().toLowerCase();
		if (code.contains("insufficient")
			|| code.contains("not_enough")
			|| "payment_required".equals(code)
			|| "insufficient_credits".equals(code))
		{
			return true;
		}
		String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
		return message.contains("not enough credit")
			|| message.contains("insufficient credit");
	}

	/**
	 * Last hiscores ranks (from pack-open), including values persisted from the previous session.
	 * @return length-6 ranks, or null if none stored yet
	 */
	public int[] getLastSidebarRanks()
	{
		return stateService.getSidebarRanks();
	}

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
			// 0 = unranked (clear cached positive rank); negatives are invalid.
			if (n < 0)
			{
				return;
			}
			ranks[i] = n;
		}
		stateService.replaceSidebarRanks(ranks);
	}

	private void debugPackOpen(String message)
	{
		if (!stateService.isDebugChatEnabled() || message == null || message.isBlank())
		{
			return;
		}
		log.info("[TCG DEBUG] {}", message);
		TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, message);
	}
}

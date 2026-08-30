package com.osrstcg.cloud.shop;

import com.google.gson.JsonObject;
import com.osrstcg.state.CardSellResult;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.credit.DuplicateSellPlanner;
import com.osrstcg.state.TcgStateService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.attest.CreditAttestQueue;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.trade.TradeCloudService;

/**
 * Server-authoritative card sells via {@code POST /api/v1/cards/sell}.
 * Never sells locked or beta instances (planner + local filter).
 * Batches requests to match the server per-request sell cap.
 */
@Slf4j
@Singleton
public final class CloudSellService
{
	/** Must stay ≤ server {@code MAX_SELL} on {@code POST /cards/sell}. */
	private static final int SELL_BATCH_SIZE = 500;

	private final CloudApiClient api;
	private final CloudSessionService session;
	private final CreditAttestQueue attestQueue;
	private final TradeCloudService tradeCloud;
	private final TcgStateService stateService;
	private final Client client;

	@Inject
	CloudSellService(
		CloudApiClient api,
		CloudSessionService session,
		CreditAttestQueue attestQueue,
		TradeCloudService tradeCloud,
		TcgStateService stateService,
		Client client)
	{
		this.api = api;
		this.session = session;
		this.attestQueue = attestQueue;
		this.tradeCloud = tradeCloud;
		this.stateService = stateService;
		this.client = client;
	}

	/**
	 * Sell planned duplicate instance IDs. {@code kept} is applied only after a successful server response.
	 */
	public CardSellResult sellDuplicates(DuplicateSellPlanner.Result plan)
	{
		long creditsBefore = stateService.getCredits();
		if (plan == null || plan.getCardsSold() <= 0 || plan.getSoldInstanceIds().isEmpty())
		{
			return CardSellResult.failed("No sellable duplicates.", creditsBefore);
		}
		if (!session.isReady())
		{
			String reason = session.needsProfileCreate()
				? "Create a profile before selling cards."
				: "Cloud offline - cannot sell cards.";
			return CardSellResult.failed(reason, creditsBefore);
		}

		List<String> instanceIds = filterSellableInstanceIds(plan.getSoldInstanceIds());
		if (instanceIds.isEmpty())
		{
			return CardSellResult.failed("No sellable duplicates.", creditsBefore);
		}

		long accountHash = client.getAccountHash();
		if (accountHash == -1L)
		{
			return CardSellResult.failed("Account not ready - try again in a moment.", creditsBefore);
		}

		try
		{
			attestQueue.flushBlocking();

			Set<String> sold = new HashSet<>();
			JsonObject lastResponse = null;
			for (int offset = 0; offset < instanceIds.size(); offset += SELL_BATCH_SIZE)
			{
				int end = Math.min(offset + SELL_BATCH_SIZE, instanceIds.size());
				List<String> batch = new ArrayList<>(instanceIds.subList(offset, end));
				JsonObject response = api.sellCards(batch, accountHash);
				lastResponse = response;
				sold.addAll(soldInstanceIdsFromResponse(response, batch));
			}

			List<OwnedCardInstance> kept = new ArrayList<>();
			for (OwnedCardInstance inst : stateService.getState().getCollectionState().getOwnedInstances())
			{
				if (inst == null)
				{
					continue;
				}
				// Never drop beta/locked even if the server somehow echoed them.
				if (inst.isBeta() || inst.isLocked())
				{
					kept.add(inst);
					continue;
				}
				String id = inst.getInstanceId();
				if (id == null || id.isBlank() || !sold.contains(id.trim()))
				{
					kept.add(inst);
				}
			}
			stateService.setCollectionInstances(kept);
			if (lastResponse != null)
			{
				session.applySidebarStats(lastResponse);
				if (lastResponse.has("revision") && !lastResponse.get("revision").isJsonNull())
				{
					long revision = lastResponse.get("revision").getAsLong();
					String stateHash = "";
					if (lastResponse.has("stateHash") && !lastResponse.get("stateHash").isJsonNull())
					{
						stateHash = lastResponse.get("stateHash").getAsString();
					}
					stateService.applyCloudSyncMarkers(revision, stateHash);
					tradeCloud.noteRevision(revision);
				}
			}
			tradeCloud.requestForcedRefresh();

			long creditsAfter = stateService.getCredits();
			return CardSellResult.succeeded(
				"Sold " + sold.size() + " cards.",
				sold.size(),
				creditsBefore,
				creditsAfter);
		}
		catch (CloudApiException ex)
		{
			log.warn("Card sell failed: {} {}", ex.getCode(), ex.getMessage());
			session.noteLockFromApiException(ex);
			return CardSellResult.failed(
				ex.getMessage() == null || ex.getMessage().isBlank()
					? "Sell failed (" + ex.getCode() + ")."
					: ex.getMessage(),
				creditsBefore);
		}
		catch (Exception e)
		{
			log.warn("Card sell failed", e);
			return CardSellResult.failed("Sell failed - try again.", creditsBefore);
		}
	}

	/**
	 * Prefer server {@code sold[]} instance ids; fall back to the requested batch.
	 */
	private static Set<String> soldInstanceIdsFromResponse(JsonObject response, List<String> batch)
	{
		Set<String> sold = new HashSet<>();
		if (response != null && response.has("sold") && response.get("sold").isJsonArray())
		{
			for (var el : response.getAsJsonArray("sold"))
			{
				if (el == null || !el.isJsonObject())
				{
					continue;
				}
				JsonObject row = el.getAsJsonObject();
				if (!row.has("instanceId") || row.get("instanceId").isJsonNull())
				{
					continue;
				}
				String id = row.get("instanceId").getAsString();
				if (id != null && !id.isBlank())
				{
					sold.add(id.trim());
				}
			}
		}
		if (sold.isEmpty() && batch != null)
		{
			sold.addAll(batch);
		}
		return sold;
	}

	/**
	 * Drop blank ids and any that still resolve to beta/locked local instances.
	 */
	private List<String> filterSellableInstanceIds(List<String> plannedIds)
	{
		Set<String> betaOrLocked = new HashSet<>();
		for (OwnedCardInstance inst : stateService.getState().getCollectionState().getOwnedInstances())
		{
			if (inst == null || inst.getInstanceId() == null || inst.getInstanceId().isBlank())
			{
				continue;
			}
			if (inst.isBeta() || inst.isLocked())
			{
				betaOrLocked.add(inst.getInstanceId().trim());
			}
		}
		List<String> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String id : plannedIds)
		{
			if (id == null || id.isBlank())
			{
				continue;
			}
			String trimmed = id.trim();
			if (betaOrLocked.contains(trimmed) || !seen.add(trimmed))
			{
				continue;
			}
			out.add(trimmed);
		}
		return out;
	}
}

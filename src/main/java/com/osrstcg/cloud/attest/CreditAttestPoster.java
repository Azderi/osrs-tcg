package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
/**
 * Sends one coalesced batch of credit events to the cloud attest endpoint and applies the response:
 * clears optimistic credits, requeues fixable rejects, updates sidebar economy stats, and forces a
 * refresh when server state changed. Must run on a background/flush thread, never the client thread.
 */
@Slf4j
final class CreditAttestPoster
{
	private final CreditAttestQueue queue;
	private final CloudApiClient api;
	private final AttestRejectRequeuer requeuer;

	CreditAttestPoster(CreditAttestQueue queue, CloudApiClient api, AttestRejectRequeuer requeuer)
	{
		this.queue = queue;
		this.api = api;
		this.requeuer = requeuer;
	}
/**
	 * Posts {@code batch} to the attest API, requeues any rejected-but-fixable events, clears optimistic
	 * credits for what the server accepted, and applies any economy/revision changes from the response.
	 *
	 * @return true if applying the response changed local credits or the trade revision
	 */
	boolean postAttestBatch(List<JsonObject> batch) throws Exception
	{
		long accountHash = queue.resolveAccountHash();
		if (accountHash == -1L)
		{
			throw new IOException("Missing account hash for credit attest flush");
		}
		String idempotencyKey = UUID.randomUUID().toString();
		JsonObject body = new JsonObject();
		body.addProperty("accountHash", Long.toString(accountHash));
		body.addProperty("idempotencyKey", idempotencyKey);
		String displayName = queue.resolveDisplayName();
		if (displayName != null && !displayName.isEmpty())
		{
			body.addProperty("displayName", displayName);
		}
		JsonArray events = new JsonArray();
		long batchOptimisticEstimate = 0L;
		for (JsonObject e : batch)
		{
			batchOptimisticEstimate += CreditAttestCoalescer.optimisticOf(e);
			events.add(CreditAttestCoalescer.forWire(e));
		}
		body.add("events", events);

		queue.debugCreditAttestSend(batch, batchOptimisticEstimate);

		long creditsBefore = queue.stateService.getCredits();
		long pendingBefore = queue.stateService.getPendingOptimisticCredits();
		long revisionBefore = queue.tradeCloud.getLastRevision();

		JsonObject response = api.attest(body);
		queue.noteAttestAfterMs(response);
		AttestRejectRequeuer.RequeueResult requeueResult = requeuer.requeueRejectedEvents(response, batch);
		queue.session.noteAttestBanFlags(response);

		long clearOptimistic = CreditAttestQueue.resolveOptimisticClearAmount(
			response, batch, batchOptimisticEstimate, requeueResult);
		if (clearOptimistic > 0L)
		{
			queue.stateService.clearOptimisticCredits(clearOptimistic);
		}

		queue.debugCreditAttestResponse(response, clearOptimistic, pendingBefore);

		boolean changed = false;
		boolean appliedEconomy = false;

		if (response.has("credits") || response.has("openedPacks") || response.has("totalCreditsGained"))
		{
			long serverCredits = response.has("credits")
				? response.get("credits").getAsLong()
				: queue.stateService.getAuthoritativeCredits();
			log.debug(
				"Credit attest economy: serverCredits={} pendingBefore={} clearOptimistic={} pendingAfter={} rejected={}",
				serverCredits,
				pendingBefore,
				clearOptimistic,
				queue.stateService.getPendingOptimisticCredits(),
				CreditAttestQueue.formatRejectedReasons(response));
			queue.session.applySidebarStats(response);
			appliedEconomy = true;
			if (queue.stateService.getCredits() != creditsBefore)
			{
				changed = true;
			}
		}
		else if (!requeueResult.reasons.isEmpty())
		{
			log.debug("Credit attest rejected without economy payload: {}", requeueResult.reasons);
		}

		if (response.has("revision") && !response.get("revision").isJsonNull())
		{
			long revision = response.get("revision").getAsLong();
			if (revision != revisionBefore)
			{
				changed = true;
			}
			queue.tradeCloud.noteRevision(revision);
		}

		long rateCapAfterMs = CreditAttestQueue.parseRateCapAfterMs(response);
		if (rateCapAfterMs > 0L)
		{
			try
			{
				queue.session.refreshCreditsFromServer(false);
				appliedEconomy = true;
				if (queue.stateService.getCredits() != creditsBefore)
				{
					changed = true;
				}
			}
			catch (Exception syncEx)
			{
				log.debug("Credits sync before rate-cap pause failed", syncEx);
			}
			queue.noteRateCapAfterMs(response);
		}
		queue.rateCapNotifier.onAttestResponse(response);

		if (appliedEconomy)
		{
			queue.notifyEconomyListener();
		}
		if (changed)
		{
			queue.tradeCloud.requestForcedRefresh();
		}
		return changed;
	}
/** True for transient I/O failures, server errors (5xx), or rate limiting — worth a retry flush. */
	static boolean isRetryableAttestFailure(Throwable ex)
	{
		if (ex instanceof IOException && !(ex instanceof CloudApiException))
		{
			return true;
		}
		if (ex instanceof CloudApiException)
		{
			CloudApiException apiEx = (CloudApiException) ex;
			return apiEx.isServerError() || apiEx.isRateLimited();
		}
		return false;
	}
}

package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class CreditAttestPoster
{
	private static final int ATTEST_RETRY_ATTEMPTS = 3;
	private static final long ATTEST_RETRY_BACKOFF_MS = 750L;

	private final CreditAttestQueue queue;
	private final CloudApiClient api;
	private final AttestRejectRequeuer requeuer;

	CreditAttestPoster(CreditAttestQueue queue, CloudApiClient api, AttestRejectRequeuer requeuer)
	{
		this.queue = queue;
		this.api = api;
		this.requeuer = requeuer;
	}

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

		JsonObject response = attestWithRetry(body);
		queue.noteAttestAfterMs(response);
		AttestRejectRequeuer.RequeueResult requeueResult = requeuer.requeueRejectedEvents(response, batch);
		queue.rateCapNotifier.onAttestResponse(response);
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

	private JsonObject attestWithRetry(JsonObject body) throws Exception
	{
		Exception last = null;
		for (int attempt = 1; attempt <= ATTEST_RETRY_ATTEMPTS; attempt++)
		{
			try
			{
				return api.attest(body);
			}
			catch (CloudApiException ex)
			{
				last = ex;
				if (!isRetryableAttestFailure(ex) || attempt >= ATTEST_RETRY_ATTEMPTS)
				{
					throw ex;
				}
				log.debug("Credit attest retry {}/{} after {} {}", attempt, ATTEST_RETRY_ATTEMPTS,
					ex.getStatus(), ex.getCode());
			}
			catch (IOException ex)
			{
				last = ex;
				if (attempt >= ATTEST_RETRY_ATTEMPTS)
				{
					throw ex;
				}
				log.debug("Credit attest retry {}/{} after IO error", attempt, ATTEST_RETRY_ATTEMPTS);
			}
			try
			{
				Thread.sleep(ATTEST_RETRY_BACKOFF_MS * attempt);
			}
			catch (InterruptedException ie)
			{
				Thread.currentThread().interrupt();
				throw last;
			}
		}
		throw last == null ? new IOException("attest failed") : last;
	}

	private static boolean isRetryableAttestFailure(CloudApiException ex)
	{
		return ex != null && (ex.isServerError() || ex.isRateLimited());
	}
}

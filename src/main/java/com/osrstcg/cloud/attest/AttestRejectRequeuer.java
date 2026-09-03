package com.osrstcg.cloud.attest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Reinterprets rejected events from an attest response and re-queues fixed-up versions of them onto
 * {@link CreditAttestQueue}. Handles {@code kill_amount_too_large} by splitting an oversized npc_kill
 * into {@link CreditAttestCoalescer#MAX_KILL_AMOUNT} chunks. Runs on the flush thread, as part of
 * {@link CreditAttestPoster#postAttestBatch}.
 */
@Slf4j
final class AttestRejectRequeuer
{
	static final String REASON_KILL_AMT_TOO_LARGE = "kill_amount_too_large";

	private final CreditAttestQueue queue;

	AttestRejectRequeuer(CreditAttestQueue queue)
	{
		this.queue = queue;
	}

	/**
	 * Walks {@code response.rejected}, matching each rejection's {@code index} back to the sent
	 * {@code batch}, and re-queues a corrected event for the reasons this class knows how to fix.
	 * Any events produced are prepended to the queue's pending list and an early flush is scheduled.
	 *
	 * @return the reject reasons seen and the indexes into {@code batch} that were requeued
	 */
	AttestRejectRequeuer.RequeueResult requeueRejectedEvents(JsonObject response, List<JsonObject> batch)
	{
		RequeueResult result = new RequeueResult();
		if (response == null || !response.has("rejected") || !response.get("rejected").isJsonArray())
		{
			return result;
		}
		List<JsonObject> requeue = new ArrayList<>();
		for (JsonElement el : response.getAsJsonArray("rejected"))
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject rejected = el.getAsJsonObject();
			String reason = JsonObjects.text(rejected, "reason");
			if (reason != null)
			{
				result.reasons.add(reason);
			}
			if (!rejected.has("index") || rejected.get("index").isJsonNull())
			{
				continue;
			}
			int index = rejected.get("index").getAsInt();
			if (index < 0 || index >= batch.size())
			{
				continue;
			}
			JsonObject original = batch.get(index);
			if (original == null)
			{
				continue;
			}

			if (!REASON_KILL_AMT_TOO_LARGE.equals(reason))
			{
				continue;
			}
			if (!CreditAttestCoalescer.TYPE_NPC_KILL.equals(JsonObjects.text(original, "type")))
			{
				continue;
			}
			JsonObject evidence = CreditAttestQueue.evidenceObject(original);
			int amount = Math.max(1, CreditAttestQueue.evidenceInt(evidence, "amount", 1));
			if (amount <= CreditAttestCoalescer.MAX_KILL_AMOUNT)
			{
				log.warn("npc_kill rejected as {} with amount {} (≤{}); leaving to server reconcile",
					REASON_KILL_AMT_TOO_LARGE, amount, CreditAttestCoalescer.MAX_KILL_AMOUNT);
				continue;
			}
			String npcName = CreditAttestQueue.evidenceString(evidence, "npcName", "");
			int combatLevel = CreditAttestQueue.evidenceInt(evidence, "combatLevel", 0);
			int npcId = CreditAttestQueue.evidenceInt(evidence, "npcId", 0);
			long at = original.has("at") && !original.get("at").isJsonNull()
				? original.get("at").getAsLong()
				: System.currentTimeMillis();
			long optimisticTotal = CreditAttestCoalescer.optimisticOf(original);
			long optimisticRemaining = optimisticTotal;
			int remaining = amount;
			while (remaining > 0)
			{
				int chunk = Math.min(CreditAttestCoalescer.MAX_KILL_AMOUNT, remaining);
				long chunkOptimistic = remaining <= chunk
					? optimisticRemaining
					: (amount <= 0 ? 0L : (optimisticTotal * chunk) / amount);
				chunkOptimistic = Math.min(chunkOptimistic, optimisticRemaining);
				JsonObject splitEvidence = new JsonObject();
				if (npcId > 0)
				{
					splitEvidence.addProperty("npcId", npcId);
				}
				if (npcName != null && !npcName.isEmpty())
				{
					splitEvidence.addProperty("npcName", npcName);
				}
				splitEvidence.addProperty("combatLevel", combatLevel);
				splitEvidence.addProperty("amount", chunk);
				JsonObject event = new JsonObject();
				event.addProperty("type", CreditAttestCoalescer.TYPE_NPC_KILL);
				event.add("evidence", splitEvidence);
				event.addProperty("at", at);
				if (chunkOptimistic > 0L)
				{
					event.addProperty(CreditAttestCoalescer.CLIENT_OPTIMISTIC_CREDITS, chunkOptimistic);
				}
				requeue.add(event);
				optimisticRemaining -= chunkOptimistic;
				remaining -= chunk;
			}
			result.requeuedIndexes.add(index);
		}
		if (!requeue.isEmpty())
		{
			queue.prependPending(requeue);
			queue.scheduleEarlyFlush();
		}
		return result;
	}

	/** Reasons seen and batch indexes requeued for a single {@link #requeueRejectedEvents} call. */
	static final class RequeueResult
	{
		final List<Integer> requeuedIndexes = new ArrayList<>();
		final List<String> reasons = new ArrayList<>();
	}
}

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
			JsonObject evidence = JsonObjects.objectOrEmpty(original, "evidence");
			int amount = Math.max(1, (int) JsonObjects.readLong(evidence, "amount", 1L));
			if (amount <= CreditAttestCoalescer.MAX_KILL_AMOUNT)
			{
				log.warn("npc_kill rejected as {} with amount {} (≤{}); leaving to server reconcile",
					REASON_KILL_AMT_TOO_LARGE, amount, CreditAttestCoalescer.MAX_KILL_AMOUNT);
				continue;
			}
			String npcName = JsonObjects.text(evidence, "npcName");
			if (npcName == null)
			{
				npcName = "";
			}
			int combatLevel = JsonObjects.readInt(evidence, "combatLevel");
			int npcId = JsonObjects.readInt(evidence, "npcId");
			long at = JsonObjects.readLong(original, "at", System.currentTimeMillis());
			requeue.addAll(CreditAttestCoalescer.splitKillEvents(
				npcId, npcName, combatLevel, amount, at, CreditAttestCoalescer.optimisticOf(original)));
			result.requeuedIndexes.add(index);
		}
		if (!requeue.isEmpty())
		{
			queue.prependPending(requeue);
			queue.attestScheduler.scheduleEarlyFlush();
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

package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.state.TcgStateService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.Text;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.cloud.trade.TradeCloudService;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory + disk-spilled queue of raw credit-earning events (xp, level-ups, npc kills, activities)
 * awaiting attestation to the cloud. Buffers events as they occur, coalesces and prioritizes them at
 * flush time via {@link CreditAttestCoalescer}, posts batches through {@link CreditAttestPoster}, and
 * requeues fixable rejects via {@link AttestRejectRequeuer}. {@link #enqueue} is called from the client
 * thread as gameplay events happen; flushes run on the injected {@link ScheduledExecutorService}, guarded
 * by internal locks, so callers never need external synchronization.
 */
@Slf4j
@Singleton
public final class CreditAttestQueue
{
	private static final long DEFAULT_ATTEST_AFTER_MS = 60_000L;
	private static final long LARGE_XP_SPIKE_DELTA = 50_000L;
	private static final int ATTEST_RETRY_ATTEMPTS = 3;
	private static final long ATTEST_RETRY_BACKOFF_MS = 750L;
	final CloudSessionService session;
	final TradeCloudService tradeCloud;
	final TcgStateService stateService;
	private final Client client;
	private final ChatMessageManager chatMessageManager;
	private final ScheduledExecutorService scheduler;
	final AttestRateCapNotifier rateCapNotifier;
	private final CreditAttestSpillStore spillStore;

	private final Object lock = new Object();
	private final Object flushGate = new Object();
	private final List<JsonObject> pendingRaw = new ArrayList<>();
	private final AtomicReference<Runnable> economyListener = new AtomicReference<>(null);
	private final AtomicBoolean earlyFlushScheduled = new AtomicBoolean(false);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong lastGoodAttestAfterMs = new AtomicLong(DEFAULT_ATTEST_AFTER_MS);
	private final AtomicInteger consecutiveRetryFailures = new AtomicInteger(0);
	private final AttestRejectRequeuer rejectRequeuer;
	private final CreditAttestPoster poster;
	private final CreditAttestScheduler attestScheduler;
	private volatile long lastAccountHash = -1L;
	private volatile String lastDisplayName;
	private volatile long spillLoadedAccountHash = -1L;

	/** Injected constructor; wires up the sub-scheduler with a flush callback and initial default interval. */
	@Inject
	CreditAttestQueue(
		CloudApiClient api,
		CloudSessionService session,
		TradeCloudService tradeCloud,
		TcgStateService stateService,
		Client client,
		ChatMessageManager chatMessageManager,
		ScheduledExecutorService scheduler,
		AttestRateCapNotifier rateCapNotifier,
		CreditAttestSpillStore spillStore)
	{
		this.session = session;
		this.tradeCloud = tradeCloud;
		this.stateService = stateService;
		this.client = client;
		this.chatMessageManager = chatMessageManager;
		this.scheduler = scheduler;
		this.rateCapNotifier = rateCapNotifier;
		this.spillStore = spillStore;
		this.rejectRequeuer = new AttestRejectRequeuer(this);
		this.poster = new CreditAttestPoster(this, api, rejectRequeuer);
		this.attestScheduler = new CreditAttestScheduler(
			scheduler, running, lastGoodAttestAfterMs, earlyFlushScheduled, DEFAULT_ATTEST_AFTER_MS,
			() -> flushSafe(false), running::get);
	}

	/** Registers a callback invoked whenever attest processing changes credits/pending totals; replaces any prior listener. */
	public void setEconomyListener(Runnable listener)
	{
		economyListener.set(listener);
	}

	/** Starts periodic flushing and loads any spilled events for the current account. */
	public void start()
	{
		attestScheduler.start();
		ensureSpillLoaded();
	}

	/** Stops periodic flushing and resets retry/rate-cap state for the next session. */
	public void stop()
	{
		attestScheduler.stop();
		consecutiveRetryFailures.set(0);
		rateCapNotifier.reset();
	}

	/** Clamps a server-provided attest interval to at least {@link #DEFAULT_ATTEST_AFTER_MS}, falling back when unset. */
	private static long resolveAttestAfterMs(long ms, long fallbackMs)
	{
		if (ms <= 0L)
		{
			long fb = fallbackMs > 0L ? fallbackMs : DEFAULT_ATTEST_AFTER_MS;
			return Math.max(DEFAULT_ATTEST_AFTER_MS, fb);
		}
		return ms;
	}

	/** Updates the periodic flush interval from the server's {@code attestAfterMs}/{@code pollAfterMs} hint, if present. */
	void noteAttestAfterMs(JsonObject response)
	{
		long fallback = lastGoodAttestAfterMs.get();
		Double parsed = JsonObjects.readNumber(response, "attestAfterMs", "pollAfterMs");
		long ms = parsed == null ? 0L : Math.round(parsed);
		lastGoodAttestAfterMs.set(resolveAttestAfterMs(ms, fallback));
	}

	/** Drops all in-memory pending events and deletes the on-disk spill for the current account, without flushing. */
	public void discardPending()
	{
		long hash;
		synchronized (lock)
		{
			pendingRaw.clear();
			hash = lastAccountHash;
			if (hash == -1L)
			{
				hash = client.getAccountHash();
			}
		}
		if (hash != -1L)
		{
			spillStore.delete(hash);
		}
	}

	/**
	 * Records one raw credit-earning event for later attestation. Filters out combat-skill xp and
	 * non-progressing level-ups, applies the optimistic credit estimate to local state immediately,
	 * persists the pending list to disk, and triggers an early flush on a coalesced-count or xp-spike
	 * threshold. No-op if the session can't currently collect attests. Expected to run on the client thread.
	 */
	public void enqueue(String type, JsonObject evidence, long optimisticCredits)
	{
		if (!session.canCollectAttests())
		{
			return;
		}
		resolveDisplayName();
		String skill = "";
		long xpDelta = 0L;
		if (CreditAttestCoalescer.TYPE_XP_CHUNK.equals(type))
		{
			skill = evidenceString(evidence, "skill", "");
			if (CreditAttestCoalescer.isCombatSkillName(skill))
			{
				return;
			}
			xpDelta = evidenceLong(evidence, "xpDelta", 0L);
			if (xpDelta <= 0L)
			{
				return;
			}
			if (CreditAttestCoalescer.isHitpointsSkillName(skill))
			{
				optimisticCredits = 0L;
			}
		}
		if (CreditAttestCoalescer.TYPE_LEVEL_UP.equals(type))
		{
			int fromLevel = evidenceInt(evidence, "fromLevel", 0);
			int toLevel = evidenceInt(evidence, "toLevel", 0);
			if (toLevel <= fromLevel)
			{
				return;
			}
		}

		JsonObject event = new JsonObject();
		event.addProperty("type", type);
		event.add("evidence", evidence == null ? new JsonObject() : evidence.deepCopy());
		event.addProperty("at", System.currentTimeMillis());
		if (optimisticCredits > 0L)
		{
			event.addProperty(CreditAttestCoalescer.CLIENT_OPTIMISTIC_CREDITS, optimisticCredits);
		}

		ensureSpillLoaded();

		boolean spikeFlush = false;
		synchronized (lock)
		{
			if (resolveAccountHashLocked() == -1L)
			{
				return;
			}
			pendingRaw.add(event);
			int coalescedEstimate = CreditAttestCoalescer.estimateCoalescedCount(pendingRaw);
			if (coalescedEstimate >= CreditAttestCoalescer.EARLY_FLUSH_COALESCED)
			{
				spikeFlush = true;
			}
			else if (CreditAttestCoalescer.TYPE_XP_CHUNK.equals(type)
				&& !CreditAttestCoalescer.isHitpointsSkillName(skill)
				&& xpDelta >= LARGE_XP_SPIKE_DELTA)
			{
				spikeFlush = true;
			}
		}
		// Reload if resolveAccountHashLocked switched accounts after the first ensureSpillLoaded.
		ensureSpillLoaded();
		persistSpillFromPending();
		applyOptimistic(optimisticCredits);
		if (spikeFlush)
		{
			attestScheduler.scheduleEarlyFlush();
		}
	}

	/** Schedules an immediate, non-blocking flush on the executor. */
	public void flushNow()
	{
		scheduler.execute(() -> flushSafe(false));
	}

	/**
	 * Runs a teardown flush synchronously on the calling thread (used on shutdown/logout). Must not be
	 * called from the client thread.
	 *
	 * @return true if the flush changed local credits or the trade revision
	 */
	public boolean flushBlocking()
	{
		return flushSafe(true);
	}

	/**
	 * Reads the current account hash from the client, caching it. On account change, clears in-memory
	 * pending events for the previous account (old spill file is left on disk for that account's next login).
	 */
	long resolveAccountHash()
	{
		synchronized (lock)
		{
			return resolveAccountHashLocked();
		}
	}

	/** Same as {@link #resolveAccountHash()} but caller already holds {@link #lock}. */
	private long resolveAccountHashLocked()
	{
		long hash = client.getAccountHash();
		if (hash != -1L)
		{
			if (lastAccountHash != -1L && lastAccountHash != hash)
			{
				pendingRaw.clear();
				spillLoadedAccountHash = -1L;
			}
			lastAccountHash = hash;
			return hash;
		}
		return lastAccountHash;
	}

	/** Reads and sanitizes the local player's RSN, caching the last known value for use when unavailable. */
	String resolveDisplayName()
	{
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			String name = Text.sanitize(client.getLocalPlayer().getName());
			if (name != null && !name.isEmpty())
			{
				lastDisplayName = name;
				return name;
			}
		}
		return lastDisplayName;
	}

	/** Loads any spilled pending events for the current account into memory, once per account hash. */
	private void ensureSpillLoaded()
	{
		long hash = resolveAccountHash();
		if (hash == -1L)
		{
			return;
		}
		synchronized (lock)
		{
			if (spillLoadedAccountHash == hash)
			{
				return;
			}
		}
		List<JsonObject> loaded = spillStore.load(hash);
		long optimisticTotal = 0L;
		synchronized (lock)
		{
			if (spillLoadedAccountHash == hash)
			{
				return;
			}
			resolveAccountHashLocked();
			if (lastAccountHash != hash)
			{
				return;
			}
			spillLoadedAccountHash = hash;
			if (!loaded.isEmpty())
			{
				pendingRaw.addAll(0, loaded);
				for (JsonObject event : loaded)
				{
					optimisticTotal += CreditAttestCoalescer.optimisticOf(event);
				}
				log.debug("Loaded {} credit attest spill event(s) for accountHash={}", loaded.size(), "<redacted>");
			}
		}
		if (optimisticTotal > 0L)
		{
			applyOptimistic(optimisticTotal);
		}
	}

	/** Writes a snapshot of the current pending list to the current account's spill file. */
	private void persistSpillFromPending()
	{
		long hash;
		List<JsonObject> snapshot;
		synchronized (lock)
		{
			hash = lastAccountHash;
			// Avoid writing an empty pending list over another account's spill before it is loaded.
			if (hash == -1L || spillLoadedAccountHash != hash)
			{
				return;
			}
			snapshot = CreditAttestSpillStore.copyEvents(pendingRaw);
		}
		spillStore.save(hash, snapshot);
	}

	/** Adds an optimistic credit estimate to local state and notifies the economy listener, if positive. */
	private void applyOptimistic(long optimisticCredits)
	{
		if (optimisticCredits > 0)
		{
			stateService.addOptimisticCredits(optimisticCredits);
			notifyEconomyListener();
		}
	}

	/** Delegates to {@link CreditAttestScheduler#scheduleEarlyFlush()}. */
	void scheduleEarlyFlush()
	{
		attestScheduler.scheduleEarlyFlush();
	}

	/**
	 * On a retryable failure (and not during teardown), schedules a backing-off retry flush up to
	 * {@link #ATTEST_RETRY_ATTEMPTS} times; otherwise resets the failure counter.
	 */
	private void maybeScheduleRetryFlush(boolean teardown, Exception ex)
	{
		if (teardown || !CreditAttestPoster.isRetryableAttestFailure(ex))
		{
			consecutiveRetryFailures.set(0);
			return;
		}
		int attempt = consecutiveRetryFailures.incrementAndGet();
		if (attempt >= ATTEST_RETRY_ATTEMPTS)
		{
			log.debug("Credit attest retry exhausted after {} failure(s)", attempt);
			return;
		}
		long delayMs = ATTEST_RETRY_BACKOFF_MS * attempt;
		log.debug("Credit attest scheduling retry {}/{} in {}ms after {}",
			attempt, ATTEST_RETRY_ATTEMPTS, delayMs, ex.toString());
		attestScheduler.scheduleRetryFlush(delayMs);
	}

	/** Inserts events at the front of the pending list, so they're the next candidates for flushing. */
	void prependPending(List<JsonObject> events)
	{
		synchronized (lock)
		{
			pendingRaw.addAll(0, events);
		}
	}

	/** Runs {@link #flush} and swallows/logs any exception so scheduler callbacks never throw. */
	private boolean flushSafe(boolean teardown)
	{
		try
		{
			return flush(teardown);
		}
		catch (CloudApiException e)
		{
			log.warn("Credit attest flush failed: {} {}", e.getCode(), e.getMessage());
			return false;
		}
		catch (Exception e)
		{
			log.warn("Credit attest flush failed", e);
			return false;
		}
	}

	/**
	 * Drains and posts all pending events, one coalesced+prioritized batch at a time, until the pending
	 * list is empty. Serialized via {@link #flushGate} so only one flush runs at a time. A batch that's
	 * only hitpoints xp is held back rather than sent. On a post failure, the batch (and any remaining
	 * coalesced events) are put back on the front of the pending list and the exception propagates.
	 *
	 * @param teardown true for a shutdown flush, which uses a looser readiness check than a normal flush
	 * @return true if any posted batch changed local credits or the trade revision
	 */
	private boolean flush(boolean teardown) throws Exception
	{
		if (teardown)
		{
			if (!session.canAttestFlush())
			{
				return false;
			}
		}
		else if (!session.isReady())
		{
			return false;
		}
		ensureSpillLoaded();
		synchronized (flushGate)
		{
			boolean changed = false;
			while (true)
			{
				List<JsonObject> raw;
				synchronized (lock)
				{
					if (pendingRaw.isEmpty())
					{
						break;
					}
					resolveAccountHashLocked();
					raw = new ArrayList<>(pendingRaw);
					pendingRaw.clear();
				}

				int rawCount = raw.size();
				List<JsonObject> coalesced = new ArrayList<>(CreditAttestCoalescer.coalesce(raw));
				log.debug("Credit attest coalesce: raw={} → coalesced={}", rawCount, coalesced.size());

				if (coalesced.isEmpty())
				{
					continue;
				}

				if (CreditAttestCoalescer.isHitpointsXpOnly(coalesced))
				{
					prependPending(coalesced);
					break;
				}

				while (!coalesced.isEmpty())
				{
					List<JsonObject> batch = CreditAttestCoalescer.takePriorityBatch(
						coalesced, CreditAttestCoalescer.MAX_BATCH);
					if (batch.isEmpty())
					{
						break;
					}
					if (CreditAttestCoalescer.isHitpointsXpOnly(batch))
					{
						prependPending(coalesced);
						prependPending(batch);
						coalesced.clear();
						break;
					}
					long started = System.currentTimeMillis();
					try
					{
						boolean batchChanged = poster.postAttestBatch(batch);
						consecutiveRetryFailures.set(0);
						changed |= batchChanged;
						persistSpillFromPending();
						log.debug("Credit attest OK: events={} durationMs={}",
							batch.size(), System.currentTimeMillis() - started);
					}
					catch (Exception ex)
					{
						prependPending(coalesced);
						prependPending(batch);
						persistSpillFromPending();
						maybeScheduleRetryFlush(teardown, ex);
						throw ex;
					}
				}
			}
			persistSpillFromPending();
			return changed;
		}
	}

	/**
	 * Determines how many optimistic credits to clear after a batch is posted: prefers the server's
	 * accepted-credits sum when present, otherwise falls back to the batch's optimistic estimate minus
	 * whatever was held back for requeued (not yet resolved) events.
	 */
	static long resolveOptimisticClearAmount(
		JsonObject response,
		List<JsonObject> batch,
		long batchOptimisticEstimate,
		AttestRejectRequeuer.RequeueResult requeueResult)
	{
		long acceptedSum = sumAcceptedCredits(response);
		if (acceptedSum >= 0L)
		{
			return acceptedSum;
		}
		long holdBack = 0L;
		if (requeueResult != null)
		{
			for (int index : requeueResult.requeuedIndexes)
			{
				if (index >= 0 && index < batch.size())
				{
					holdBack += CreditAttestCoalescer.optimisticOf(batch.get(index));
				}
			}
		}
		return Math.max(0L, batchOptimisticEstimate - holdBack);
	}

	/** Sums the credit amounts of {@code response.accepted}, or -1 if the field is absent/malformed. */
	private static long sumAcceptedCredits(JsonObject response)
	{
		if (response == null || !response.has("accepted") || !response.get("accepted").isJsonArray())
		{
			return -1L;
		}
		JsonArray accepted = response.getAsJsonArray("accepted");
		if (accepted.size() == 0)
		{
			return 0L;
		}
		long sum = 0L;
		boolean sawAmount = false;
		for (JsonElement el : accepted)
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			JsonObject row = el.getAsJsonObject();
			Double amount = JsonObjects.readNumber(row, "credits", "amount", "awarded", "creditDelta");
			if (amount != null)
			{
				sawAmount = true;
				sum += Math.max(0L, Math.round(amount));
			}
		}
		return sawAmount ? sum : -1L;
	}

	/** Posts a debug-chat summary of an outgoing attest batch (event type counts, optimistic estimate), if debug chat is on. */
	void debugCreditAttestSend(List<JsonObject> batch, long optimisticEstimate)
	{
		if (!stateService.isDebugChatEnabled() || batch == null || batch.isEmpty())
		{
			return;
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (JsonObject event : batch)
		{
			String type = "?";
			if (event != null && event.has("type") && !event.get("type").isJsonNull())
			{
				type = event.get("type").getAsString();
			}
			counts.merge(type, 1, Integer::sum);
		}
		StringBuilder summary = new StringBuilder();
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			if (summary.length() > 0)
			{
				summary.append(", ");
			}
			summary.append(entry.getKey()).append(" x").append(entry.getValue());
		}
		String message = "Sending " + batch.size() + " credit events to server: " + summary;
		if (optimisticEstimate > 0L)
		{
			message += " (" + optimisticEstimate + " credits)";
		}
		TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, message);
	}

	/** Posts a debug-chat summary of an attest response (credits, cleared/pending optimistic, rejects), if debug chat is on. */
	void debugCreditAttestResponse(JsonObject response, long clearOptimistic, long pendingBefore)
	{
		if (!stateService.isDebugChatEnabled() || response == null)
		{
			return;
		}
		StringBuilder message = new StringBuilder("Server attest response");
		if (response.has("credits") && !response.get("credits").isJsonNull())
		{
			message.append(": credits=").append(response.get("credits").getAsLong());
		}
		if (clearOptimistic > 0L)
		{
			message.append(", cleared optimistic=").append(clearOptimistic);
		}
		long pendingAfter = stateService.getPendingOptimisticCredits();
		if (pendingBefore != pendingAfter)
		{
			message.append(", pending ").append(pendingBefore).append(" -> ").append(pendingAfter);
		}
		String rejected = formatRejectedReasons(response);
		if (rejected != null && !"[]".equals(rejected))
		{
			message.append(", rejected=").append(rejected);
		}
		TcgPluginGameMessages.queueDebugGameMessage(chatMessageManager, message.toString());
	}

	/** Formats {@code response.rejected} reasons as a bracketed, comma-separated list for logging/chat. */
	static String formatRejectedReasons(JsonObject response)
	{
		if (response == null || !response.has("rejected") || !response.get("rejected").isJsonArray())
		{
			return "[]";
		}
		StringBuilder sb = new StringBuilder("[");
		boolean first = true;
		for (JsonElement el : response.getAsJsonArray("rejected"))
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			String reason = JsonObjects.text(el.getAsJsonObject(), "reason");
			if (reason == null)
			{
				continue;
			}
			if (!first)
			{
				sb.append(',');
			}
			first = false;
			sb.append(reason);
		}
		sb.append(']');
		return sb.toString();
	}

	/** Invokes the registered economy listener, if any. */
	void notifyEconomyListener()
	{
		Runnable listener = economyListener.get();
		if (listener != null)
		{
			listener.run();
		}
	}

	/** Returns an event's {@code evidence} object, or an empty object if missing/not an object. */
	static JsonObject evidenceObject(JsonObject event)
	{
		if (event != null && event.has("evidence") && event.get("evidence").isJsonObject())
		{
			return event.getAsJsonObject("evidence");
		}
		return new JsonObject();
	}

	/** Reads a string field from an evidence object, returning {@code defaultValue} if missing/null. */
	static String evidenceString(JsonObject evidence, String key, String defaultValue)
	{
		if (evidence == null || !evidence.has(key) || evidence.get(key).isJsonNull())
		{
			return defaultValue;
		}
		String value = evidence.get(key).getAsString();
		return value == null ? defaultValue : value;
	}

	/** Reads an int field from an evidence object, returning {@code defaultValue} if missing/null. */
	static int evidenceInt(JsonObject evidence, String key, int defaultValue)
	{
		if (evidence == null || !evidence.has(key) || evidence.get(key).isJsonNull())
		{
			return defaultValue;
		}
		return evidence.get(key).getAsInt();
	}

	/** Reads a long field from an evidence object, returning {@code defaultValue} if missing/null. */
	static long evidenceLong(JsonObject evidence, String key, long defaultValue)
	{
		if (evidence == null || !evidence.has(key) || evidence.get(key).isJsonNull())
		{
			return defaultValue;
		}
		return evidence.get(key).getAsLong();
	}
}

package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * Queues raw credit events and coalesces them immediately before {@code POST /credits/attest}.
 * Unacked raw events are spilled under the current account's profile dir for crash recovery.
 * Flush triggers: server {@code attestAfterMs} (default ~60s); logout / shutdown / sell drain;
 * pack open when authoritative credits are below that pack's price; early flush near ~80 coalesced
 * events; large XP spikes.
 *
 * @see CreditAttestCoalescer
 * @see CreditAttestSpillStore
 */
@Slf4j
@Singleton
public final class CreditAttestQueue
{
	private static final long DEFAULT_ATTEST_AFTER_MS = 60_000L;
	/** Floor only for missing/invalid values; server {@code attestAfterMs} is otherwise authoritative. */
	private static final long MIN_ATTEST_AFTER_MS = 60_000L;
	/** Optional early flush when a single xp_chunk is this large (still coalesced first). */
	private static final long LARGE_XP_SPIKE_DELTA = 50_000L;
	private final CloudSessionService session;
	private final TradeCloudService tradeCloud;
	private final TcgStateService stateService;
	private final Client client;
	private final ScheduledExecutorService scheduler;
	private final AttestRateCapNotifier rateCapNotifier;
	private final ChatMessageManager chatMessageManager;
	private final CreditAttestSpillStore spillStore;

	private final Object lock = new Object();
	/** Serializes coalesce→attest drains. */
	private final Object flushGate = new Object();
	/** Raw intake - never POSTed without {@link CreditAttestCoalescer#coalesce}. */
	private final List<JsonObject> pendingRaw = new ArrayList<>();
	private final AtomicReference<Runnable> economyListener = new AtomicReference<>(null);
	private final AtomicBoolean earlyFlushScheduled = new AtomicBoolean(false);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong lastGoodAttestAfterMs = new AtomicLong(DEFAULT_ATTEST_AFTER_MS);
	private final AttestRejectRequeuer rejectRequeuer;
	private final CreditAttestPoster poster;
	private final CreditAttestScheduler attestScheduler;
	/** Last account hash seen while logged in - used if client clears hash before teardown flush. */
	private volatile long lastAccountHash = -1L;
	/** Last sanitized RSN seen while logged in - used if local player clears before teardown flush. */
	private volatile String lastDisplayName;
	/** Account hash for which {@link #spillStore} was already loaded into {@link #pendingRaw}. */
	private volatile long spillLoadedForAccountHash = -1L;

	@Inject
	CreditAttestQueue(
		CloudApiClient api,
		CloudSessionService session,
		TradeCloudService tradeCloud,
		TcgStateService stateService,
		Client client,
		ScheduledExecutorService scheduler,
		AttestRateCapNotifier rateCapNotifier,
		ChatMessageManager chatMessageManager,
		CreditAttestSpillStore spillStore)
	{
		this.session = session;
		this.tradeCloud = tradeCloud;
		this.stateService = stateService;
		this.client = client;
		this.scheduler = scheduler;
		this.rateCapNotifier = rateCapNotifier;
		this.chatMessageManager = chatMessageManager;
		this.spillStore = spillStore;
		this.rejectRequeuer = new AttestRejectRequeuer(this);
		this.poster = new CreditAttestPoster(this, api, rejectRequeuer);
		this.attestScheduler = new CreditAttestScheduler(
			scheduler, running, lastGoodAttestAfterMs, earlyFlushScheduled, DEFAULT_ATTEST_AFTER_MS,
			() -> flushSafe(false), running::get);
	}

	TcgStateService stateService()
	{
		return stateService;
	}

	TradeCloudService tradeCloud()
	{
		return tradeCloud;
	}

	CloudSessionService session()
	{
		return session;
	}

	AttestRateCapNotifier rateCapNotifier()
	{
		return rateCapNotifier;
	}

	public void setEconomyListener(Runnable listener)
	{
		economyListener.set(listener);
	}

	/** Idempotent while already scheduled so world hops do not reset the attest timer. */
	public void start()
	{
		attestScheduler.start();
		ensureSpillLoaded();
	}

	public void stop()
	{
		attestScheduler.stop();
		spillLoadedForAccountHash = -1L;
		if (rateCapNotifier != null)
		{
			rateCapNotifier.reset();
		}
	}

	/**
	 * Server {@code attestAfterMs} is authoritative (no client max). Non-positive
	 * values fall back to the previous good delay or the default floor.
	 */
	private static long resolveAttestAfterMs(long ms, long fallbackMs)
	{
		if (ms <= 0L)
		{
			long fb = fallbackMs > 0L ? fallbackMs : DEFAULT_ATTEST_AFTER_MS;
			return Math.max(MIN_ATTEST_AFTER_MS, fb);
		}
		return ms;
	}

	void noteAttestAfterMs(JsonObject response)
	{
		long fallback = lastGoodAttestAfterMs.get();
		Long parsed = null;
		if (response != null && response.has("attestAfterMs") && !response.get("attestAfterMs").isJsonNull())
		{
			try
			{
				parsed = response.get("attestAfterMs").getAsLong();
			}
			catch (RuntimeException ignored)
			{
				// keep last-good
			}
		}
		else if (response != null && response.has("pollAfterMs") && !response.get("pollAfterMs").isJsonNull())
		{
			try
			{
				parsed = response.get("pollAfterMs").getAsLong();
			}
			catch (RuntimeException ignored)
			{
				// keep last-good
			}
		}
		lastGoodAttestAfterMs.set(resolveAttestAfterMs(parsed != null ? parsed : 0L, fallback));
	}

	/** Drop unsent attest events. Does not touch optimistic display credits. */
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

	public void enqueue(String type, JsonObject evidence, long optimisticCredits)
	{
		if (!session.canCollectAttests())
		{
			return;
		}
		resolveDisplayName();
		if (CreditAttestCoalescer.TYPE_XP_CHUNK.equals(type))
		{
			String skill = evidenceString(evidence, "skill", "");
			if (CreditAttestCoalescer.isCombatSkillName(skill))
			{
				return;
			}
			long xpDelta = evidenceLong(evidence, "xpDelta", 0L);
			if (xpDelta <= 0L)
			{
				return;
			}
			// Hitpoints is tracked but never grants optimistic credits.
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
			rememberAccountHashLocked();
			pendingRaw.add(event);
			int coalescedEstimate = CreditAttestCoalescer.estimateCoalescedCount(pendingRaw);
			if (coalescedEstimate >= CreditAttestCoalescer.EARLY_FLUSH_COALESCED)
			{
				spikeFlush = true;
			}
			else if (CreditAttestCoalescer.TYPE_XP_CHUNK.equals(type)
				&& !CreditAttestCoalescer.isHitpointsSkillName(evidenceString(evidence, "skill", ""))
				&& evidenceLong(evidence, "xpDelta", 0L) >= LARGE_XP_SPIKE_DELTA)
			{
				spikeFlush = true;
			}
		}
		persistSpillFromPending();
		applyOptimistic(optimisticCredits);
		if (spikeFlush)
		{
			attestScheduler.scheduleEarlyFlush();
		}
	}

	/**
	 * Schedule a pending-attest drain on the plugin scheduler (non-blocking).
	 * Safe to call from the client thread. Pack open / logout / shutdown that must finish
	 * before continuing should use {@link #flushBlocking()}.
	 */
	public void flushNow()
	{
		scheduler.execute(() -> flushSafe(false));
	}

	/**
	 * Drain pending attests on the calling thread (blocking HTTP).
	 * Use from pack buy, logout, and ClientShutdown waited futures - not from ClientThread.
	 * Allows a best-effort POST while a token still exists even if the UI marked the session offline.
	 */
	public boolean flushBlocking()
	{
		return flushSafe(true);
	}

	private void rememberAccountHashLocked()
	{
		long hash = client.getAccountHash();
		if (hash != -1L)
		{
			if (lastAccountHash != -1L && lastAccountHash != hash)
			{
				spillLoadedForAccountHash = -1L;
			}
			lastAccountHash = hash;
		}
	}

	long resolveAccountHash()
	{
		long hash = client.getAccountHash();
		if (hash != -1L)
		{
			if (lastAccountHash != -1L && lastAccountHash != hash)
			{
				spillLoadedForAccountHash = -1L;
			}
			lastAccountHash = hash;
			return hash;
		}
		return lastAccountHash;
	}

	/** @return sanitized local player name, or last cached name if the client already cleared it */
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

	/**
	 * Load crash-recovery spill into {@link #pendingRaw} once per account hash.
	 * Safe to call from enqueue / start; IO runs outside {@link #lock}.
	 */
	private void ensureSpillLoaded()
	{
		long hash = resolveAccountHash();
		if (hash == -1L)
		{
			return;
		}
		synchronized (lock)
		{
			if (spillLoadedForAccountHash == hash)
			{
				return;
			}
		}
		List<JsonObject> loaded = spillStore.load(hash);
		long optimisticTotal = 0L;
		synchronized (lock)
		{
			if (spillLoadedForAccountHash == hash)
			{
				return;
			}
			rememberAccountHashLocked();
			if (lastAccountHash != hash)
			{
				return;
			}
			spillLoadedForAccountHash = hash;
			if (!loaded.isEmpty())
			{
				pendingRaw.addAll(0, loaded);
				for (JsonObject event : loaded)
				{
					optimisticTotal += CreditAttestCoalescer.optimisticOf(event);
				}
				log.debug("Loaded {} credit attest spill event(s) for accountHash={}", loaded.size(), hash);
			}
		}
		if (optimisticTotal > 0L)
		{
			applyOptimistic(optimisticTotal);
		}
	}

	/** Snapshot {@link #pendingRaw} under lock and persist (delete when empty). */
	private void persistSpillFromPending()
	{
		long hash;
		List<JsonObject> snapshot;
		synchronized (lock)
		{
			hash = lastAccountHash;
			if (hash == -1L)
			{
				return;
			}
			snapshot = CreditAttestSpillStore.copyEvents(pendingRaw);
		}
		spillStore.save(hash, snapshot);
	}

	private void applyOptimistic(long optimisticCredits)
	{
		if (optimisticCredits > 0)
		{
			stateService.addOptimisticCredits(optimisticCredits);
			notifyEconomyListener();
		}
	}

	void scheduleEarlyFlush()
	{
		attestScheduler.scheduleEarlyFlush();
	}

	void prependPending(List<JsonObject> events)
	{
		synchronized (lock)
		{
			pendingRaw.addAll(0, events);
		}
	}

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
			// Offline buffer: keep pending + spill; do not spam the API while disconnected.
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
					rememberAccountHashLocked();
					raw = new ArrayList<>(pendingRaw);
					pendingRaw.clear();
				}

				int rawCount = raw.size();
				List<JsonObject> coalesced = new ArrayList<>(CreditAttestCoalescer.coalesce(raw));
				CoalesceBreakdown breakdown = CoalesceBreakdown.of(coalesced);
				log.debug(
					"Credit attest coalesce: raw={} → coalesced={} (xpSkills={}, levelUps={}, killEvents={}, activities={}, other={})",
					rawCount,
					coalesced.size(),
					breakdown.xpSkills,
					breakdown.levelUps,
					breakdown.killEvents,
					breakdown.activities,
					breakdown.other);

				if (coalesced.isEmpty())
				{
					continue;
				}

				// Hitpoints XP alone is not worth a round-trip; hold until something else joins.
				if (CreditAttestCoalescer.isHitpointsXpOnly(coalesced))
				{
					synchronized (lock)
					{
						pendingRaw.addAll(0, coalesced);
					}
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
						synchronized (lock)
						{
							pendingRaw.addAll(0, coalesced);
							pendingRaw.addAll(0, batch);
						}
						coalesced.clear();
						break;
					}
					long started = System.currentTimeMillis();
					try
					{
						boolean batchChanged = poster.postAttestBatch(batch);
						changed |= batchChanged;
						persistSpillFromPending();
						log.debug("Credit attest OK: events={} durationMs={}",
							batch.size(), System.currentTimeMillis() - started);
					}
					catch (Exception ex)
					{
						synchronized (lock)
						{
							// Preserve order: failed batch then remaining coalesced, then any new raw.
							pendingRaw.addAll(0, coalesced);
							pendingRaw.addAll(0, batch);
						}
						persistSpillFromPending();
						throw ex;
					}
				}
			}
			// Drain finished (or pending emptied mid-loop) - drop spill when nothing remains.
			persistSpillFromPending();
			return changed;
		}
	}

	/**
	 * Prefer summed {@code accepted[]} credit amounts when present; otherwise clear the batch estimate
	 * minus optimistic stamped on rejected events that were requeued (still pending).
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

	/** @return sum of accepted credit amounts, or {@code -1} when the array is missing/unusable */
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
			Long amount = firstLong(row, "credits", "amount", "awarded", "creditDelta");
			if (amount != null)
			{
				sawAmount = true;
				sum += Math.max(0L, amount);
			}
		}
		return sawAmount ? sum : -1L;
	}

	private static Long firstLong(JsonObject row, String... keys)
	{
		if (row == null || keys == null)
		{
			return null;
		}
		for (String key : keys)
		{
			if (row.has(key) && !row.get(key).isJsonNull())
			{
				try
				{
					return row.get(key).getAsLong();
				}
				catch (RuntimeException ignored)
				{
					// try next alias
				}
			}
		}
		return null;
	}

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

	void notifyEconomyListener()
	{
		Runnable listener = economyListener.get();
		if (listener != null)
		{
			listener.run();
		}
	}

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

	static JsonObject evidenceObject(JsonObject event)
	{
		if (event != null && event.has("evidence") && event.get("evidence").isJsonObject())
		{
			return event.getAsJsonObject("evidence");
		}
		return new JsonObject();
	}

	static String evidenceString(JsonObject evidence, String key, String defaultValue)
	{
		if (evidence == null || !evidence.has(key) || evidence.get(key).isJsonNull())
		{
			return defaultValue;
		}
		String value = evidence.get(key).getAsString();
		return value == null ? defaultValue : value;
	}

	static int evidenceInt(JsonObject evidence, String key, int defaultValue)
	{
		if (evidence == null || !evidence.has(key) || evidence.get(key).isJsonNull())
		{
			return defaultValue;
		}
		return evidence.get(key).getAsInt();
	}

	private static long evidenceLong(JsonObject evidence, String key, long defaultValue)
	{
		if (evidence == null || !evidence.has(key) || evidence.get(key).isJsonNull())
		{
			return defaultValue;
		}
		return evidence.get(key).getAsLong();
	}

	private static final class CoalesceBreakdown
	{
		private final int xpSkills;
		private final int levelUps;
		private final int killEvents;
		private final int activities;
		private final int other;

		private CoalesceBreakdown(int xpSkills, int levelUps, int killEvents, int activities, int other)
		{
			this.xpSkills = xpSkills;
			this.levelUps = levelUps;
			this.killEvents = killEvents;
			this.activities = activities;
			this.other = other;
		}

		private static CoalesceBreakdown of(List<JsonObject> events)
		{
			int xp = 0;
			int levels = 0;
			int kills = 0;
			int acts = 0;
			int other = 0;
			for (JsonObject e : events)
			{
				String type = JsonObjects.text(e, "type");
				if (CreditAttestCoalescer.TYPE_XP_CHUNK.equals(type))
				{
					xp++;
				}
				else if (CreditAttestCoalescer.TYPE_LEVEL_UP.equals(type))
				{
					levels++;
				}
				else if (CreditAttestCoalescer.TYPE_NPC_KILL.equals(type))
				{
					kills++;
				}
				else if (CreditAttestCoalescer.TYPE_ACTIVITY.equals(type))
				{
					acts++;
				}
				else
				{
					other++;
				}
			}
			return new CoalesceBreakdown(xp, levels, kills, acts, other);
		}
	}
}

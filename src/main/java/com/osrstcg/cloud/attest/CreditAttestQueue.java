package com.osrstcg.cloud.attest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.state.TcgStateService;
import java.util.ArrayList;
import java.util.List;
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
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Singleton
public final class CreditAttestQueue
{
	private static final long DEFAULT_ATTEST_AFTER_MS = 60_000L;
	private static final long LARGE_XP_SPIKE_DELTA = 50_000L;
	private final CloudSessionService session;
	private final TradeCloudService tradeCloud;
	private final TcgStateService stateService;
	private final Client client;
	private final ChatMessageManager chatMessageManager;
	private final ScheduledExecutorService scheduler;
	private final AttestRateCapNotifier rateCapNotifier;
	private final CreditAttestSpillStore spillStore;

	private final Object lock = new Object();
	private final Object flushGate = new Object();
	private final List<JsonObject> pendingRaw = new ArrayList<>();
	private final AtomicReference<Runnable> economyListener = new AtomicReference<>(null);
	private final AtomicBoolean earlyFlushScheduled = new AtomicBoolean(false);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong lastGoodAttestAfterMs = new AtomicLong(DEFAULT_ATTEST_AFTER_MS);
	private final AttestRejectRequeuer rejectRequeuer;
	private final CreditAttestPoster poster;
	private final CreditAttestScheduler attestScheduler;
	private volatile long lastAccountHash = -1L;
	private volatile String lastDisplayName;
	private volatile long spillLoadedForAccountHash = -1L;

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

	private static long resolveAttestAfterMs(long ms, long fallbackMs)
	{
		if (ms <= 0L)
		{
			long fb = fallbackMs > 0L ? fallbackMs : DEFAULT_ATTEST_AFTER_MS;
			return Math.max(DEFAULT_ATTEST_AFTER_MS, fb);
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

	public void flushNow()
	{
		scheduler.execute(() -> flushSafe(false));
	}

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
							pendingRaw.addAll(0, coalesced);
							pendingRaw.addAll(0, batch);
						}
						persistSpillFromPending();
						throw ex;
					}
				}
			}
			persistSpillFromPending();
			return changed;
		}
	}

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

	static long evidenceLong(JsonObject evidence, String key, long defaultValue)
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

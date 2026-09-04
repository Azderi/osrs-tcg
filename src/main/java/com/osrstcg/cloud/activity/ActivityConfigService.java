package com.osrstcg.cloud.activity;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivitiesConfigResponse;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivityChatRuleDto;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivityConfigDto;
import com.osrstcg.cloud.activity.ActivityConfigModels.KillCreditMultiplierDto;
import com.osrstcg.cloud.activity.ActivityConfigModels.NpcExclusionsDto;
import com.osrstcg.cloud.api.CloudApiClient;
import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.util.AtomicFiles;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Fetches, caches, and compiles the server-driven activity config (chat-based credit rules and NPC
 * exclusions) used to award activity credits. Holds the current {@link CompiledActivityConfig} in an
 * {@link AtomicReference} for lock-free reads; network refreshes run asynchronously on {@link #scheduler}
 * and never block callers of the getters. A copy is persisted to disk so the last-good config survives
 * restarts and cloud API failures.
 */
@Slf4j
@Singleton
public final class ActivityConfigService
{
	private static final long QUIET_POLL_MINUTES = 10L;

	private final CloudApiClient api;
	private final Gson gson;
	private final ScheduledExecutorService scheduler;

	private final AtomicReference<CompiledActivityConfig> compiled =
		new AtomicReference<>(CompiledActivityConfig.EMPTY);
	private final AtomicBoolean ensureInFlight = new AtomicBoolean(false);
	private final AtomicBoolean softRefreshScheduled = new AtomicBoolean(false);
	private final Object pollLock = new Object();
	private ScheduledFuture<?> quietPollFuture;

	/** Wires collaborators and registers to be notified when the cloud API observes a new remote version. */
	@Inject
	ActivityConfigService(CloudApiClient api, Gson gson, ScheduledExecutorService scheduler)
	{
		this.api = api;
		this.gson = gson;
		this.scheduler = scheduler;
		api.setActivitiesVersionListener(this::noteRemoteVersion);
	}

	/** Synchronously loads the on-disk cached config, if any, so a compiled config is available before any network fetch. Blocks on disk I/O; call off the client thread during startup. */
	public void loadDiskCacheIfPresent()
	{
		Path file = diskCacheFile();
		if (!Files.isRegularFile(file))
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			ActivityConfigDto dto = gson.fromJson(reader, ActivityConfigDto.class);
			if (dto == null)
			{
				return;
			}
			applyDto(dto, false);
			log.info("Loaded activity config from disk (version={}, chatRules={})",
				getVersion(), getChatRules().size());
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.warn("Failed reading activity config disk cache {}", file, ex);
		}
	}

	/** Kicks off an async, best-effort refresh without blocking the caller. */
	public void prefetchAsync()
	{
		scheduler.execute(this::ensureFreshSafe);
	}

	/** Starts the periodic quiet poll and triggers an immediate async refresh; call once per login. */
	public void refreshOnLogin()
	{
		startQuietPoll();
		scheduler.execute(this::ensureFreshSafe);
	}

	/** Cancels the periodic quiet poll, if running; call on logout. */
	public void stopQuietPoll()
	{
		synchronized (pollLock)
		{
			if (quietPollFuture != null)
			{
				quietPollFuture.cancel(false);
				quietPollFuture = null;
			}
		}
	}

	/**
	 * Callback for {@link CloudApiClient}'s version listener: if {@code remoteVersion} differs from the
	 * cached version, schedules a single async refresh (coalesced via {@link #softRefreshScheduled} so
	 * repeated notifications don't queue duplicate refreshes).
	 */
	public void noteRemoteVersion(String remoteVersion)
	{
		if (remoteVersion == null || remoteVersion.isBlank())
		{
			return;
		}
		String remote = remoteVersion.trim();
		String local = getVersion();
		if (!local.isEmpty() && local.equals(remote))
		{
			return;
		}
		if (!softRefreshScheduled.compareAndSet(false, true))
		{
			return;
		}
		scheduler.execute(() ->
		{
			try
			{
				ensureFreshSafe();
			}
			finally
			{
				softRefreshScheduled.set(false);
			}
		});
	}

	/**
	 * Synchronously checks the remote version and, if stale, fetches and applies a fresh config; a single
	 * refresh runs at a time ({@link #ensureInFlight} guards re-entry) and failures are logged and swallowed,
	 * keeping the last-good compiled config. Performs blocking network I/O; call from {@link #scheduler}, not
	 * the client thread.
	 */
	public void ensureFresh()
	{
		if (!ensureInFlight.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			ensureFreshLocked();
		}
		catch (CloudApiException | IOException ex)
		{
			if (ex instanceof CloudApiException
				&& "consent_required".equals(((CloudApiException) ex).getCode()))
			{
				log.debug("Activity config refresh skipped until cloud consent");
				return;
			}
			log.warn("Activity config refresh failed; keeping last-good ({})", ex.toString());
		}
		finally
		{
			ensureInFlight.set(false);
		}
	}

	/** {@link #ensureFresh()} wrapped with a catch-all so a scheduled task never dies from an unexpected exception. */
	private void ensureFreshSafe()
	{
		try
		{
			ensureFresh();
		}
		catch (Exception ex)
		{
			log.warn("Activity config refresh failed; keeping last-good", ex);
		}
	}

	/**
	 * Does the actual version check and conditional-GET fetch: skips the fetch entirely if a lightweight
	 * version check confirms the cache is current, and skips applying the body on a 304. Blocking network I/O.
	 */
	private void ensureFreshLocked() throws CloudApiException, IOException
	{
		String cachedVersion = getVersion();
		String remoteVersion = null;
		try
		{
			remoteVersion = api.getActivitiesVersion();
		}
		catch (Exception ex)
		{
			log.debug("Activity config version check failed", ex);
		}

		if (remoteVersion != null && !remoteVersion.isBlank()
			&& !cachedVersion.isEmpty()
			&& cachedVersion.equals(remoteVersion.trim()))
		{
			log.debug("Activity config up to date ({})", cachedVersion);
			return;
		}

		ActivitiesConfigResponse response = api.getActivities(
			cachedVersion.isEmpty() ? null : cachedVersion);
		if (response.isNotModified())
		{
			log.debug("Activity config 304 Not Modified ({})", cachedVersion);
			return;
		}
		ActivityConfigDto body = response.getBody();
		if (body == null)
		{
			return;
		}
		applyDto(body, true);
		log.info("Refreshed activity config (version={}, chatRules={})",
			getVersion(), getChatRules().size());
	}

	/** Version of the currently compiled config; non-blocking. */
	public String getVersion()
	{
		return compiled.get().getVersion();
	}

	/** Chat rules from the currently compiled config; non-blocking. */
	public List<CompiledActivityConfig.CompiledChatRule> getChatRules()
	{
		return compiled.get().getChatRules();
	}

	/** Currently compiled config snapshot; non-blocking. */
	public CompiledActivityConfig getCompiled()
	{
		return compiled.get();
	}

	/** Starts a fixed-rate background refresh poll if one isn't already running. */
	private void startQuietPoll()
	{
		synchronized (pollLock)
		{
			if (quietPollFuture != null && !quietPollFuture.isCancelled())
			{
				return;
			}
			quietPollFuture = scheduler.scheduleAtFixedRate(
				this::ensureFreshSafe,
				QUIET_POLL_MINUTES,
				QUIET_POLL_MINUTES,
				TimeUnit.MINUTES);
		}
	}

	/** Compiles {@code dto} and publishes it as the current config, optionally persisting the raw DTO to disk. */
	private void applyDto(ActivityConfigDto dto, boolean persistDisk)
	{
		CompiledActivityConfig next = compile(dto);
		compiled.set(next);
		if (persistDisk)
		{
			persistDiskCache(dto);
		}
	}

	/**
	 * Converts a raw {@link ActivityConfigDto} into an immutable {@link CompiledActivityConfig}: compiles
	 * each chat rule (dropping invalid ones), expands NPC exclusion ids/ranges into a flat id set, and
	 * parses per-NPC kill credit multipliers.
	 */
	static CompiledActivityConfig compile(ActivityConfigDto dto)
	{
		if (dto == null)
		{
			return CompiledActivityConfig.EMPTY;
		}

		List<CompiledActivityConfig.CompiledChatRule> rules = new ArrayList<>();
		if (dto.chatRules != null)
		{
			for (ActivityChatRuleDto rule : dto.chatRules)
			{
				CompiledActivityConfig.CompiledChatRule compiledRule = compileChatRule(rule);
				if (compiledRule != null)
				{
					rules.add(compiledRule);
				}
			}
		}

		Set<Integer> npcIds = new HashSet<>();
		NpcExclusionsDto excl = dto.npcExclusions;
		if (excl != null)
		{
			if (excl.npcIds != null)
			{
				for (Integer id : excl.npcIds)
				{
					if (id != null)
					{
						npcIds.add(id);
					}
				}
			}
			if (excl.npcIdRanges != null)
			{
				for (List<Integer> range : excl.npcIdRanges)
				{
					if (range == null || range.size() < 2 || range.get(0) == null || range.get(1) == null)
					{
						continue;
					}
					int lo = Math.min(range.get(0), range.get(1));
					int hi = Math.max(range.get(0), range.get(1));
					for (int i = lo; i <= hi; i++)
					{
						npcIds.add(i);
					}
				}
			}
		}

		Map<Integer, Double> killMultipliers = new HashMap<>();
		if (dto.killCreditMultipliers != null)
		{
			for (Map.Entry<String, KillCreditMultiplierDto> entry : dto.killCreditMultipliers.entrySet())
			{
				if (entry == null || entry.getKey() == null || entry.getValue() == null)
				{
					continue;
				}
				int npcId;
				try
				{
					npcId = Integer.parseInt(entry.getKey().trim());
				}
				catch (NumberFormatException ex)
				{
					continue;
				}
				double multiplier = Math.max(0.0, entry.getValue().multiplier);
				killMultipliers.put(npcId, multiplier);
			}
		}

		String version = dto.version == null ? "" : dto.version.trim();
		return new CompiledActivityConfig(version, rules, npcIds, killMultipliers);
	}

	/**
	 * Compiles one raw chat rule, or returns null if it's missing required fields or (for a regex rule)
	 * has an invalid pattern.
	 */
	private static CompiledActivityConfig.CompiledChatRule compileChatRule(ActivityChatRuleDto rule)
	{
		if (rule == null || rule.activityId == null || rule.activityId.isBlank()
			|| rule.value == null || rule.value.isEmpty())
		{
			return null;
		}
		String match = rule.match == null ? "prefix" : rule.match.trim().toLowerCase(Locale.ENGLISH);
		if ("regex".equals(match))
		{
			try
			{
				Pattern pattern = Pattern.compile(rule.value);
				return new CompiledActivityConfig.CompiledChatRule(
					rule.activityId.trim(), rule.credits, rule.label, null, pattern);
			}
			catch (PatternSyntaxException ex)
			{
				log.warn("Skipping invalid activity regex for {}: {}", rule.activityId, ex.getMessage());
				return null;
			}
		}
		return new CompiledActivityConfig.CompiledChatRule(
			rule.activityId.trim(), rule.credits, rule.label, rule.value, null);
	}

	/** Best-effort atomic write of the raw DTO JSON to the disk cache; failures are logged and swallowed. */
	private void persistDiskCache(ActivityConfigDto dto)
	{
		Path target = diskCacheFile();
		try
		{
			AtomicFiles.writeString(target, gson.toJson(dto), StandardCharsets.UTF_8);
		}
		catch (Exception ex)
		{
			log.debug("Activity config disk cache write failed", ex);
		}
	}

	/** Directory under the RuneLite home dir holding the activity config disk cache. */
	private static Path diskCacheDir()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "activities");
	}

	/** Path to the cached raw activity config JSON file. */
	private static Path diskCacheFile()
	{
		return diskCacheDir().resolve("activities.json");
	}
}

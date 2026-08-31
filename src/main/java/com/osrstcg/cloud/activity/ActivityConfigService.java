package com.osrstcg.cloud.activity;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivitiesConfigResponse;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivityChatRuleDto;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivityConfigDto;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

	@Inject
	ActivityConfigService(CloudApiClient api, Gson gson, ScheduledExecutorService scheduler)
	{
		this.api = api;
		this.gson = gson;
		this.scheduler = scheduler;
		api.setActivitiesVersionListener(this::noteRemoteVersion);
	}

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

	public void prefetchAsync()
	{
		scheduler.execute(this::ensureFreshSafe);
	}

	public void refreshOnLogin()
	{
		startQuietPoll();
		scheduler.execute(this::ensureFreshSafe);
	}

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

	public String getVersion()
	{
		return compiled.get().getVersion();
	}

	public List<CompiledActivityConfig.CompiledChatRule> getChatRules()
	{
		return compiled.get().getChatRules();
	}

	public CompiledActivityConfig getCompiled()
	{
		return compiled.get();
	}

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

	private void applyDto(ActivityConfigDto dto, boolean persistDisk)
	{
		CompiledActivityConfig next = compile(dto);
		compiled.set(next);
		if (persistDisk)
		{
			persistDiskCache(dto);
		}
	}

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

		String version = dto.version == null ? "" : dto.version.trim();
		return new CompiledActivityConfig(version, rules, npcIds);
	}

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

	private static Path diskCacheDir()
	{
		return Path.of(RuneLite.RUNELITE_DIR.getAbsolutePath(), "OSRS-TCG", "activities");
	}

	private static Path diskCacheFile()
	{
		return diskCacheDir().resolve("activities.json");
	}
}

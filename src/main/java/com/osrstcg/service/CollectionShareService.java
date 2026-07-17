package com.osrstcg.service;

import com.google.gson.Gson;
import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.TcgPublicStats;
import com.osrstcg.model.TcgState;
import com.osrstcg.persist.CollectionShareCredentialsStore;
import com.osrstcg.util.TcgPluginGameMessages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.util.Text;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Creates a web share and PUTs share-safe collection snapshots to osrs-tcg.xyz.
 */
@Slf4j
@Singleton
public class CollectionShareService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String DEFAULT_PUBLIC_BASE = "https://osrs-tcg.xyz";
	private static final String API_BASE = DEFAULT_PUBLIC_BASE + "/api/v1";
	private static final long DEBOUNCE_MS = 1500L;
	private static final long KEEPALIVE_PERIOD_MS = 4L * 60L * 1000L + 30L * 1000L; // 4.5 min
	private static final long FAILURE_COOLDOWN_MS = 30_000L;
	private static final int MAX_CONSECUTIVE_FAILURES_BEFORE_BACKOFF = 3;
	private static final long BACKOFF_COOLDOWN_MS = 120_000L;
	private static final String INVALID_API_KEY_STATUS =
		"Invalid API key — change it in plugin settings to resume";

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final Client client;
	private final OsrsTcgConfig config;
	private final TcgStateService stateService;
	private final TcgPublicStatsCalculator statsCalculator;
	private final CollectionShareCredentialsStore credentialsStore;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;

	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean syncInFlight = new AtomicBoolean(false);
	private final AtomicBoolean syncQueued = new AtomicBoolean(false);
	private final AtomicLong lastFailureAtMs = new AtomicLong(0L);
	private final AtomicLong consecutiveFailures = new AtomicLong(0L);
	private final AtomicReference<String> statusText = new AtomicReference<>("Idle");
	private final AtomicReference<String> lastPublicUrl = new AtomicReference<>(null);
	private final AtomicReference<String> catalogVersion = new AtomicReference<>("1.0.0");
	private final AtomicReference<Runnable> statusListener = new AtomicReference<>(null);
	/** When set, matches the rejected {@code webShareApiKey} value; sync stays off until the key changes. */
	private final AtomicReference<String> rejectedApiKey = new AtomicReference<>(null);
	private final AtomicReference<WebShareIndicatorState> indicatorState =
		new AtomicReference<>(WebShareIndicatorState.HIDDEN);

	public enum WebShareIndicatorState
	{
		HIDDEN,
		LIVE,
		ERROR
	}

	private final Object debounceLock = new Object();
	private ScheduledFuture<?> debounceFuture;
	private ScheduledFuture<?> keepaliveFuture;

	@Inject
	CollectionShareService(
		OkHttpClient okHttpClient,
		Gson gson,
		Client client,
		OsrsTcgConfig config,
		TcgStateService stateService,
		TcgPublicStatsCalculator statsCalculator,
		CollectionShareCredentialsStore credentialsStore,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager)
	{
		this.httpClient = okHttpClient.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.client = client;
		this.config = config;
		this.stateService = stateService;
		this.statsCalculator = statsCalculator;
		this.credentialsStore = credentialsStore;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
	}

	public void start()
	{
		if (!started.compareAndSet(false, true))
		{
			return;
		}
		catalogVersion.set(loadCatalogVersion());
		stateService.setCollectionShareNotify(this::onCollectionChanged);
		keepaliveFuture = scheduler.scheduleAtFixedRate(
			this::keepaliveTick,
			KEEPALIVE_PERIOD_MS,
			KEEPALIVE_PERIOD_MS,
			TimeUnit.MILLISECONDS);
		if (!config.webShareEnabled())
		{
			setIndicatorState(WebShareIndicatorState.HIDDEN);
			setStatus("Disabled");
		}
		else if (!hasApiKey())
		{
			setIndicatorState(WebShareIndicatorState.HIDDEN);
			setStatus("API key required");
		}
		else
		{
			setIndicatorState(WebShareIndicatorState.ERROR);
			setStatus("Enabled — waiting to sync");
			scheduleSyncImmediate();
		}
	}

	public void stop()
	{
		if (!started.compareAndSet(true, false))
		{
			return;
		}
		stateService.setCollectionShareNotify(null);
		synchronized (debounceLock)
		{
			if (debounceFuture != null)
			{
				debounceFuture.cancel(false);
				debounceFuture = null;
			}
		}
		if (keepaliveFuture != null)
		{
			keepaliveFuture.cancel(false);
			keepaliveFuture = null;
		}
	}

	public void onConfigChanged()
	{
		if (!started.get())
		{
			return;
		}

		clearApiKeyRejectionIfKeyChanged();

		if (!config.webShareEnabled())
		{
			setIndicatorState(WebShareIndicatorState.HIDDEN);
			setStatus("Disabled");
			debugWebAlbum("Sharing disabled");
			cancelPendingDebounce();
			return;
		}
		if (!hasApiKey())
		{
			setIndicatorState(WebShareIndicatorState.HIDDEN);
			setStatus("API key required");
			debugWebAlbum("API key required");
			cancelPendingDebounce();
			return;
		}
		if (isApiKeyBlocked())
		{
			setIndicatorState(WebShareIndicatorState.ERROR);
			setStatus(INVALID_API_KEY_STATUS);
			debugWebAlbum(INVALID_API_KEY_STATUS);
			cancelPendingDebounce();
			return;
		}

		setIndicatorState(WebShareIndicatorState.ERROR);
		setStatus("Enabled — syncing…");
		debugWebAlbum("Sharing enabled; syncing");
		scheduleSyncImmediate();
	}

	public void onLoginOrProfileReady()
	{
		if (!started.get() || !canAttemptSync())
		{
			return;
		}
		// LOGGED_IN often fires before local player name is available; retry briefly.
		consecutiveFailures.set(0L);
		scheduleSyncImmediate();
		scheduler.schedule(this::retrySyncAfterLogin, 750L, TimeUnit.MILLISECONDS);
		scheduler.schedule(this::retrySyncAfterLogin, 2000L, TimeUnit.MILLISECONDS);
		scheduler.schedule(this::retrySyncAfterLogin, 5000L, TimeUnit.MILLISECONDS);
	}

	private void retrySyncAfterLogin()
	{
		if (!started.get() || !canAttemptSync())
		{
			return;
		}
		if (resolveDisplayName() == null)
		{
			return;
		}
		if (getIndicatorState() == WebShareIndicatorState.LIVE)
		{
			return;
		}
		scheduleSyncImmediate();
	}

	/** Call when returning to the login screen so the indicator turns red while sharing is enabled. */
	public void onLoggedOut()
	{
		if (!started.get() || !config.webShareEnabled())
		{
			return;
		}
		setIndicatorState(WebShareIndicatorState.ERROR);
		setStatus("Waiting for login");
		debugWebAlbum("Logged out — web album sync paused");
	}

	public void onCollectionChanged()
	{
		if (!started.get() || !canAttemptSync())
		{
			return;
		}
		scheduleSyncDebounced();
	}

	public String getStatusText()
	{
		return statusText.get();
	}

	public String getPublicUrl()
	{
		String cached = lastPublicUrl.get();
		if (cached != null && !cached.isEmpty())
		{
			return cached;
		}
		String name = resolveDisplayName();
		if (name == null)
		{
			return null;
		}
		return publicUrlForDisplayName(name);
	}

	/**
	 * True after a successful PUT while sharing remains enabled (public album should be visible).
	 */
	public boolean isPublicAlbumLive()
	{
		return getIndicatorState() == WebShareIndicatorState.LIVE;
	}

	public WebShareIndicatorState getIndicatorState()
	{
		if (!config.webShareEnabled())
		{
			return WebShareIndicatorState.HIDDEN;
		}
		return indicatorState.get();
	}

	/** Optional UI refresh hook (e.g. sidebar status label). */
	public void setStatusListener(Runnable listener)
	{
		statusListener.set(listener);
	}

	private void keepaliveTick()
	{
		if (!started.get() || !canAttemptSync())
		{
			return;
		}
		if (resolveDisplayName() == null)
		{
			return;
		}
		scheduleSyncImmediate();
	}

	private void cancelPendingDebounce()
	{
		synchronized (debounceLock)
		{
			if (debounceFuture != null)
			{
				debounceFuture.cancel(false);
				debounceFuture = null;
			}
		}
	}

	private boolean canAttemptSync()
	{
		return config.webShareEnabled() && hasApiKey() && !isApiKeyBlocked();
	}

	private boolean hasApiKey()
	{
		return !configuredApiKey().isEmpty();
	}

	private String configuredApiKey()
	{
		String key = config.webShareApiKey();
		if (key == null)
		{
			return "";
		}
		return key.trim();
	}

	private boolean isApiKeyBlocked()
	{
		String rejected = rejectedApiKey.get();
		return rejected != null && rejected.equals(configuredApiKey());
	}

	private void clearApiKeyRejectionIfKeyChanged()
	{
		String rejected = rejectedApiKey.get();
		if (rejected == null)
		{
			return;
		}
		if (!rejected.equals(configuredApiKey()))
		{
			rejectedApiKey.set(null);
			consecutiveFailures.set(0L);
		}
	}

	private void markApiKeyRejected()
	{
		String key = configuredApiKey();
		if (!key.isEmpty())
		{
			rejectedApiKey.set(key);
		}
		setIndicatorState(WebShareIndicatorState.ERROR);
		setStatus(INVALID_API_KEY_STATUS);
		debugWebAlbum(INVALID_API_KEY_STATUS);
		cancelPendingDebounce();
		syncQueued.set(false);
	}

	private void scheduleSyncDebounced()
	{
		synchronized (debounceLock)
		{
			if (debounceFuture != null)
			{
				debounceFuture.cancel(false);
			}
			debounceFuture = scheduler.schedule(this::runSyncPipeline, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
		}
	}

	private void scheduleSyncImmediate()
	{
		synchronized (debounceLock)
		{
			if (debounceFuture != null)
			{
				debounceFuture.cancel(false);
			}
			debounceFuture = scheduler.schedule(this::runSyncPipeline, 0L, TimeUnit.MILLISECONDS);
		}
	}

	private void runSyncPipeline()
	{
		if (!config.webShareEnabled())
		{
			return;
		}
		if (!hasApiKey())
		{
			setStatus("API key required");
			debugWebAlbum("Sync skipped: API key required");
			return;
		}
		if (isApiKeyBlocked())
		{
			setStatus(INVALID_API_KEY_STATUS);
			return;
		}
		if (!syncInFlight.compareAndSet(false, true))
		{
			syncQueued.set(true);
			debugWebAlbum("Sync already in flight; queued another pass");
			return;
		}
		try
		{
			if (shouldSkipDueToBackoff())
			{
				setIndicatorState(WebShareIndicatorState.ERROR);
				setStatus("Paused after errors — retrying soon");
				debugWebAlbum("Sync paused after errors; retrying soon");
				return;
			}
			debugWebAlbum("Sync starting");
			ensureCredentialsAndPut();
		}
		catch (Exception ex)
		{
			noteFailure("Sync failed: " + briefError(ex));
			log.warn("Web collection share sync failed", ex);
		}
		finally
		{
			syncInFlight.set(false);
			if (syncQueued.compareAndSet(true, false) && canAttemptSync())
			{
				scheduleSyncDebounced();
			}
		}
	}

	private boolean shouldSkipDueToBackoff()
	{
		long failures = consecutiveFailures.get();
		if (failures < MAX_CONSECUTIVE_FAILURES_BEFORE_BACKOFF)
		{
			return false;
		}
		long since = System.currentTimeMillis() - lastFailureAtMs.get();
		long cooldown = failures >= MAX_CONSECUTIVE_FAILURES_BEFORE_BACKOFF ? BACKOFF_COOLDOWN_MS : FAILURE_COOLDOWN_MS;
		return since < cooldown;
	}

	private void ensureCredentialsAndPut() throws IOException
	{
		String apiKey = configuredApiKey();
		if (apiKey.isEmpty())
		{
			setStatus("API key required");
			return;
		}
		if (isApiKeyBlocked())
		{
			setStatus(INVALID_API_KEY_STATUS);
			return;
		}

		String displayName = resolveDisplayName();
		if (displayName == null)
		{
			// Not an upload failure — keep red from logout / waiting, and let login retries finish the sync.
			if (indicatorState.get() != WebShareIndicatorState.LIVE)
			{
				setIndicatorState(WebShareIndicatorState.ERROR);
			}
			setStatus("Waiting for login");
			debugWebAlbum("Sync waiting for login");
			return;
		}

		if (!credentialsStore.hasCredentials())
		{
			debugWebAlbum("Creating share for " + displayName);
			if (!createShare(displayName))
			{
				return;
			}
		}

		String shareId = credentialsStore.getShareId();
		String writeToken = credentialsStore.getWriteToken();
		if (shareId == null || writeToken == null)
		{
			noteFailure("Missing share credentials");
			return;
		}

		debugWebAlbum("Uploading collection for " + displayName + " (share " + shareId + ")");
		int code = putCollection(shareId, writeToken, displayName, apiKey);
		if (code == 401)
		{
			log.warn("Web share PUT rejected: invalid or missing API key");
			markApiKeyRejected();
			return;
		}
		if (code == 404)
		{
			log.info("Web share missing (HTTP 404); recreating share");
			debugWebAlbum("Share missing (HTTP 404); recreating");
			credentialsStore.clear();
			if (!createShare(displayName))
			{
				return;
			}
			shareId = credentialsStore.getShareId();
			writeToken = credentialsStore.getWriteToken();
			if (shareId == null || writeToken == null)
			{
				noteFailure("Could not recreate share");
				return;
			}
			code = putCollection(shareId, writeToken, displayName, apiKey);
			if (code == 401)
			{
				log.warn("Web share PUT rejected after recreate: invalid API key");
				markApiKeyRejected();
				return;
			}
		}

		if (code >= 200 && code < 300)
		{
			consecutiveFailures.set(0L);
			rejectedApiKey.set(null);
			String url = publicUrlForDisplayName(displayName);
			lastPublicUrl.set(url);
			setIndicatorState(WebShareIndicatorState.LIVE);
			setStatus("Synced " + formatClock(Instant.now()));
			debugWebAlbum("Synced " + displayName + " -> " + url);
			log.debug("Web collection share synced for {} (HTTP {})", displayName, code);
			return;
		}

		if (code == 409)
		{
			noteFailure("Display name already shared by another player");
			return;
		}

		noteFailure("Upload failed (HTTP " + code + ")");
	}

	/**
	 * @return true if credentials were stored
	 */
	private boolean createShare(String displayName) throws IOException
	{
		HttpUrl url = HttpUrl.parse(API_BASE + "/shares");
		if (url == null)
		{
			throw new IOException("Invalid API URL");
		}

		Map<String, Object> body = Map.of("displayName", displayName);
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.header("Accept", "application/json")
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = readBody(response);
			if (response.code() == 409)
			{
				noteFailure("Display name already shared by another player");
				return false;
			}
			if (!response.isSuccessful())
			{
				noteFailure("Create share failed (HTTP " + response.code() + ")");
				throw new IOException("create share HTTP " + response.code() + ": " + truncate(responseBody));
			}

			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = gson.fromJson(responseBody, Map.class);
			if (parsed == null)
			{
				throw new IOException("create share response was empty");
			}
			Object shareIdObj = parsed.get("shareId");
			Object writeTokenObj = parsed.get("writeToken");
			Object urlObj = parsed.get("url");
			String shareId = shareIdObj == null ? null : String.valueOf(shareIdObj);
			String writeToken = writeTokenObj == null ? null : String.valueOf(writeTokenObj);
			String publicUrl = urlObj == null || String.valueOf(urlObj).isEmpty()
				? publicUrlForDisplayName(displayName)
				: String.valueOf(urlObj);
			if (shareId == null || shareId.isEmpty() || writeToken == null || writeToken.isEmpty())
			{
				throw new IOException("create share response missing credentials");
			}
			credentialsStore.save(shareId, writeToken);
			lastPublicUrl.set(publicUrl);
			log.info("Created web collection share {}", shareId);
			debugWebAlbum("Created share " + shareId + " → " + publicUrl);
			return true;
		}
	}

	private int putCollection(String shareId, String writeToken, String displayName, String apiKey) throws IOException
	{
		HttpUrl url = HttpUrl.parse(API_BASE + "/shares/" + shareId + "/collection");
		if (url == null)
		{
			throw new IOException("Invalid share PUT URL");
		}

		CollectionState collection;
		TcgPublicStats stats;
		synchronized (stateService)
		{
			TcgState state = stateService.getState();
			collection = state.getCollectionState();
			stats = statsCalculator.computeForShare(collection);
		}

		Map<String, Object> payload = CollectionShareSnapshotBuilder.buildPayload(
			catalogVersion.get(),
			displayName,
			stats,
			collection,
			Instant.now());

		Request request = new Request.Builder()
			.url(url)
			.put(RequestBody.create(JSON, gson.toJson(payload)))
			.header("X-Api-Key", apiKey)
			.header("Authorization", "Bearer " + writeToken)
			.header("Accept", "application/json")
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			String responseBody = readBody(response);
			if (!response.isSuccessful())
			{
				log.warn("Web share PUT rejected (HTTP {}): {}", response.code(), truncate(responseBody));
				debugWebAlbum("Upload rejected (HTTP " + response.code() + ")");
			}
			return response.code();
		}
	}

	private String resolveDisplayName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return null;
		}
		String sanitized = Text.sanitize(client.getLocalPlayer().getName());
		if (sanitized == null)
		{
			return null;
		}
		String trimmed = sanitized.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static String publicUrlForDisplayName(String displayName)
	{
		return DEFAULT_PUBLIC_BASE + "/" + encodePathSegment(displayName);
	}

	private static String encodePathSegment(String value)
	{
		// Space → %20; keep letters/digits/_/- unescaped for readable OSRS names
		StringBuilder sb = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
				|| c == '_' || c == '-')
			{
				sb.append(c);
			}
			else if (c == ' ')
			{
				sb.append("%20");
			}
			else
			{
				sb.append('%');
				sb.append(String.format("%02X", (int) c));
			}
		}
		return sb.toString();
	}

	private void setStatus(String text)
	{
		statusText.set(text == null ? "" : text);
		Runnable listener = statusListener.get();
		if (listener != null)
		{
			try
			{
				listener.run();
			}
			catch (Exception ex)
			{
				log.debug("Web share status listener failed", ex);
			}
		}
	}

	private void noteFailure(String message)
	{
		consecutiveFailures.incrementAndGet();
		lastFailureAtMs.set(System.currentTimeMillis());
		setIndicatorState(WebShareIndicatorState.ERROR);
		setStatus(message);
		debugWebAlbum(message);
	}

	private void setIndicatorState(WebShareIndicatorState state)
	{
		indicatorState.set(state == null ? WebShareIndicatorState.HIDDEN : state);
	}

	private void debugWebAlbum(String message)
	{
		if (!config.debugMessages() || message == null || message.isEmpty())
		{
			return;
		}
		log.info("[OSRS TCG] Web album: {}", message);
		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, "Web album: " + message);
	}

	private static String loadCatalogVersion()
	{
		try (InputStream in = CollectionShareService.class.getResourceAsStream("/VERSION"))
		{
			if (in == null)
			{
				return "1.0.0";
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[256];
			int n;
			while ((n = in.read(chunk)) >= 0)
			{
				buffer.write(chunk, 0, n);
			}
			String text = buffer.toString(StandardCharsets.UTF_8.name()).trim();
			return text.isEmpty() ? "1.0.0" : text;
		}
		catch (Exception ex)
		{
			return "1.0.0";
		}
	}

	private static String readBody(Response response) throws IOException
	{
		ResponseBody body = response.body();
		return body == null ? "" : body.string();
	}

	private static String truncate(String value)
	{
		if (value == null || value.isEmpty())
		{
			return "";
		}
		String normalized = value.replace('\n', ' ').trim();
		return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
	}

	private static String briefError(Exception ex)
	{
		String msg = ex.getMessage();
		if (msg == null || msg.isEmpty())
		{
			return ex.getClass().getSimpleName();
		}
		return msg.length() <= 80 ? msg : msg.substring(0, 80) + "...";
	}

	private static String formatClock(Instant instant)
	{
		String s = instant.toString();
		// 2026-07-17T00:08:43.123Z → show time portion briefly
		int t = s.indexOf('T');
		if (t >= 0 && s.length() >= t + 9)
		{
			return s.substring(t + 1, Math.min(t + 9, s.length())) + " UTC";
		}
		return s;
	}
}

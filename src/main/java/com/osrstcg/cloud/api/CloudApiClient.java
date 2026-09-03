package com.osrstcg.cloud.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivitiesConfigResponse;
import com.osrstcg.cloud.activity.ActivityConfigModels.ActivityConfigDto;
import com.osrstcg.cloud.catalog.LiveCardsResponse;
import com.osrstcg.cloud.session.CloudTokenStore;
import static com.osrstcg.cloud.api.JsonObjects.objectOrEmpty;
import static com.osrstcg.cloud.api.JsonObjects.readBoolean;
import static com.osrstcg.cloud.api.JsonObjects.readNumber;
import static com.osrstcg.cloud.api.JsonObjects.text;
import static com.osrstcg.cloud.api.JsonObjects.textTrimmed;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import com.osrstcg.cloud.trade.TradeInboxItem;

/**
 * Blocking HTTP client for the cloud API. Every method that issues a request performs a
 * synchronous network call and must not be invoked on the client thread; callers are expected
 * to run these off-thread (e.g. via a scheduler). Handles auth headers, one-shot token refresh
 * on 401, consent gating before the account has migrated, and mapping HTTP error bodies to
 * {@link CloudApiException}.
 */
@Slf4j
@Singleton
public final class CloudApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;
	private final CloudTokenStore tokenStore;
	private final ProfileKeyHasher profileKeyHasher;
	private volatile String cachedCatalogVersion;
	private volatile Runnable staleRefreshHandler;
	private volatile Consumer<CloudApiException> accountLockHandler;
	private volatile Consumer<String> activitiesVersionCb;
	/** Nesting depth for {@link #openConsentTraffic()} (create-profile after Yes). */
	private final ThreadLocal<Integer> consentTrafficDepth = ThreadLocal.withInitial(() -> 0);

	/** Builds a dedicated {@link OkHttpClient} from the injected one with fixed connect/read/write timeouts. */
	@Inject
	CloudApiClient(
		OkHttpClient okHttpClient,
		Gson gson,
		CloudTokenStore tokenStore,
		ProfileKeyHasher profileKeyHasher)
	{
		this.http = okHttpClient.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.writeTimeout(60, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.tokenStore = tokenStore;
		this.profileKeyHasher = profileKeyHasher;
	}

	/**
	 * Allows API calls while {@code cloudMigrated} is still false - only for the consent
	 * action itself (pair / create profile after the user clicks Yes).
	 */
	public AutoCloseable openConsentTraffic()
	{
		consentTrafficDepth.set(consentTrafficDepth.get() + 1);
		return () ->
		{
			int next = consentTrafficDepth.get() - 1;
			if (next <= 0)
			{
				consentTrafficDepth.remove();
			}
			else
			{
				consentTrafficDepth.set(next);
			}
		};
	}

	/**
	 * Blocks all cloud HTTP until the user has accepted create-profile consent, except
	 * while {@link #openConsentTraffic()} is open on this thread.
	 */
	private void requireCloudConsentAllowed() throws CloudApiException
	{
		if (tokenStore.isMigrated() || consentTrafficDepth.get() > 0)
		{
			return;
		}
		throw new CloudApiException(0, "consent_required",
			"Accept cloud consent before contacting the server");
	}

	/** Called when a token refresh fails because the refresh token itself is stale, so credentials get cleared. */
	public void setStaleRefreshHandler(Runnable handler)
	{
		staleRefreshHandler = handler;
	}

	/** Called whenever a request fails with an account-banned or account-quarantined error. */
	public void setAccountLockHandler(Consumer<CloudApiException> handler)
	{
		accountLockHandler = handler;
	}

	/** Called with the trimmed {@code X-Activities-Version} header value whenever a response carries one. */
	public void setActivitiesVersionListener(Consumer<String> listener)
	{
		activitiesVersionCb = listener;
	}

	/** Last catalog version observed from any response, or null if none yet. */
	public String getCachedCatalogVersion()
	{
		return cachedCatalogVersion;
	}

	/** {@code GET /health} (unauthenticated). Blocking call. */
	public JsonObject getHealth() throws CloudApiException, IOException
	{
		JsonObject json = request("GET", "/health", null, false);
		cacheCatalogVersionFrom(json);
		return json;
	}

	/** {@code GET /packs}. Blocking call. */
	public JsonObject getPacks() throws CloudApiException, IOException
	{
		JsonObject json = requestAuthed("GET", "/packs", null);
		cacheCatalogVersionFrom(json);
		return json;
	}

	/**
	 * {@code GET /catalog/cards/live}, sending {@code cachedCatalogVersion} as an If-None-Match
	 * ETag so the server can respond 304. Blocking call.
	 */
	public LiveCardsResponse getLiveCards(String cachedCatalogVersion) throws CloudApiException, IOException
	{
		try (Response response = getWithOptionalEtag("/catalog/cards/live", cachedCatalogVersion))
		{
			String versionHeader = response.header("X-Catalog-Version");
			if (versionHeader != null && !versionHeader.isBlank())
			{
				setCachedCatalogVersion(versionHeader);
			}
			notifyActivitiesVersion(response.header("X-Activities-Version"));
			if (response.code() == 304)
			{
				return LiveCardsResponse.notModified(versionHeader);
			}
			String text = readSuccessfulBody(response);
			JsonObject body = parseObject(text);
			String version = versionHeader;
			if (version == null || version.isBlank())
			{
				version = stripQuotes(response.header("ETag"));
			}
			if (version != null && !version.isBlank())
			{
				setCachedCatalogVersion(version);
			}
			return LiveCardsResponse.ok(body, text, version);
		}
	}

	/** {@code GET /players/{name}/stats} (unauthenticated, name URL-encoded with spaces as underscores). Blocking call. */
	public JsonObject getPublicPlayerStats(String displayName) throws CloudApiException, IOException
	{
		String name = displayName == null ? "" : displayName.trim();
		String slug = name.replace(' ', '_');
		String encoded = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
		return request("GET", "/players/" + encoded + "/stats", null, false);
	}

	/** Updates {@link #cachedCatalogVersion} if non-blank; no-op otherwise. */
	private void setCachedCatalogVersion(String catalogVersion)
	{
		if (catalogVersion != null && !catalogVersion.isBlank())
		{
			cachedCatalogVersion = catalogVersion.trim();
		}
	}

	/** Extracts and caches the {@code catalogVersion} field from a JSON body, if present. */
	private void cacheCatalogVersionFrom(JsonObject json)
	{
		setCachedCatalogVersion(textTrimmed(json, "catalogVersion"));
	}

	/** {@code POST /auth/pair/start} (unauthenticated). Blocking call. */
	public JsonObject pairStart(String displayName, String profileKeyHash, long accountHash)
		throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("displayName", displayName);
		body.addProperty("profileKeyHash", profileKeyHash);
		body.addProperty("accountHash", Long.toString(accountHash));
		return request("POST", "/auth/pair/start", body, false);
	}

	/** {@code POST /auth/refresh} (unauthenticated). Blocking call. */
	public JsonObject refresh(String refreshToken, String profileKeyHash) throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("refreshToken", refreshToken);
		body.addProperty("profileKeyHash", profileKeyHash);
		return request("POST", "/auth/refresh", body, false);
	}

	/** {@code POST /auth/web-code}, requesting a one-time code to sign into the web app. Blocking call. */
	public JsonObject webCode(String next) throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		if (next != null && !next.isBlank())
		{
			body.addProperty("next", next.trim());
		}
		return requestAuthed("POST", "/auth/web-code", body);
	}

	/** {@code GET /me/stats}. Blocking call. */
	public JsonObject getStats() throws CloudApiException, IOException
	{
		return requestAuthed("GET", "/me/stats", null);
	}

	/** {@code GET /me/state}. Blocking call. */
	public JsonObject getState() throws CloudApiException, IOException
	{
		return requestAuthed("GET", "/me/state", null);
	}

	/** {@code GET /me/cards}, paginated (limit clamped to 1-500). Blocking call. */
	public JsonObject getCardsPage(int limit, String cursor) throws CloudApiException, IOException
	{
		int pageLimit = Math.max(1, Math.min(limit, 500));
		StringBuilder path = new StringBuilder("/me/cards?limit=").append(pageLimit);
		if (cursor != null && !cursor.isBlank())
		{
			path.append("&cursor=").append(URLEncoder.encode(cursor.trim(), StandardCharsets.UTF_8));
		}
		return requestAuthed("GET", path.toString(), null);
	}

	/** {@code POST /credits/attest}. Blocking call. */
	public JsonObject attest(JsonObject body) throws CloudApiException, IOException
	{
		return requestAuthed("POST", "/credits/attest", body);
	}

	/** {@code POST /credits/settle-hiscores}. Blocking call. */
	public JsonObject settleHiscores(String displayName, long accountHash, boolean snapshot)
		throws CloudApiException, IOException
	{
		return settleHiscores(displayName, accountHash, snapshot, null, 0L, 0);
	}

	/**
	 * {@code POST /credits/settle-hiscores} with optional login-cached {@code events} and overflow
	 * metadata (unpaid credits beyond the client 500k cap).
	 */
	public JsonObject settleHiscores(
		String displayName,
		long accountHash,
		boolean snapshot,
		List<JsonObject> events,
		long overflowCredits,
		int overflowEventCount)
		throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("displayName", displayName);
		body.addProperty("accountHash", Long.toString(accountHash));
		if (snapshot)
		{
			body.addProperty("snapshot", true);
		}
		if (events != null && !events.isEmpty())
		{
			JsonArray arr = new JsonArray();
			for (JsonObject event : events)
			{
				if (event != null)
				{
					arr.add(event);
				}
			}
			body.add("events", arr);
		}
		if (overflowCredits > 0L)
		{
			body.addProperty("overflowCredits", overflowCredits);
		}
		if (overflowEventCount > 0)
		{
			body.addProperty("overflowEventCount", overflowEventCount);
		}
		return requestAuthed("POST", "/credits/settle-hiscores", body);
	}

	/** {@link #settleHiscores(String, long, boolean)} without requesting a snapshot. */
	public JsonObject settleHiscores(String displayName, long accountHash) throws CloudApiException, IOException
	{
		return settleHiscores(displayName, accountHash, false, null, 0L, 0);
	}

	/**
	 * Login settle with optional cached events (no snapshot). Always used for post-login settle,
	 * including when {@code events} is null/empty.
	 */
	public JsonObject settleHiscores(
		String displayName,
		long accountHash,
		List<JsonObject> events,
		long overflowCredits,
		int overflowEventCount)
		throws CloudApiException, IOException
	{
		return settleHiscores(displayName, accountHash, false, events, overflowCredits, overflowEventCount);
	}

	/** {@code GET /config/activities/version} (unauthenticated). Blocking call. */
	public String getActivitiesVersion() throws CloudApiException, IOException
	{
		String version = text(request("GET", "/config/activities/version", null, false), "version");
		return version == null ? "" : version;
	}

	/**
	 * {@code GET /config/activities}, sending {@code cachedVersion} as an If-None-Match ETag so
	 * the server can respond 304. Blocking call.
	 */
	public ActivitiesConfigResponse getActivities(String cachedVersion) throws CloudApiException, IOException
	{
		try (Response response = getWithOptionalEtag("/config/activities", cachedVersion))
		{
			notifyActivitiesVersion(response.header("X-Activities-Version"));
			if (response.code() == 304)
			{
				return ActivitiesConfigResponse.notModified();
			}
			String text = readBody(response);
			JsonObject json = parseObject(text);
			if (!response.isSuccessful())
			{
				throw httpError(response.code(), text);
			}
			ActivityConfigDto dto = gson.fromJson(json, ActivityConfigDto.class);
			if (dto == null)
			{
				dto = new ActivityConfigDto();
			}
			if ((dto.version == null || dto.version.isBlank()) && response.header("ETag") != null)
			{
				dto.version = stripQuotes(response.header("ETag"));
			}
			return ActivitiesConfigResponse.ok(dto);
		}
	}

	/** {@code POST /packs/open}. Blocking call. */
	public JsonObject openPack(JsonObject body) throws CloudApiException, IOException
	{
		return requestAuthed("POST", "/packs/open", body);
	}

	/** Adds the required {@code accountHash} field to a trade mutator request body. */
	public static JsonObject withPluginAccountHash(JsonObject body, long accountHash)
	{
		if (accountHash == -1L)
		{
			throw new IllegalArgumentException("accountHash is required for plugin trade mutators");
		}
		JsonObject out = body == null ? new JsonObject() : body;
		out.addProperty("accountHash", Long.toString(accountHash));
		return out;
	}

	/** {@code POST /trades}. Blocking call. */
	public JsonObject createTrade(String partnerDisplayName, long accountHash) throws CloudApiException, IOException
	{
		JsonObject body = withPluginAccountHash(new JsonObject(), accountHash);
		body.addProperty("partnerDisplayName", partnerDisplayName);
		return requestAuthed("POST", "/trades", body);
	}

	/** {@code GET /me/trades/inbox}, optionally filtered to entries since {@code sinceRevision}. Blocking call. */
	public JsonObject getTradeInbox(long accountHash, Long sinceRevision) throws CloudApiException, IOException
	{
		String path = "/me/trades/inbox?accountHash=" + accountHash;
		if (sinceRevision != null)
		{
			path += "&sinceRevision=" + sinceRevision;
		}
		return requestAuthed("GET", path, null);
	}

	/** {@code POST /me/trades/{tradeId}/ack-notify}. Blocking call. */
	public JsonObject ackTradeNotify(String tradeId, long accountHash) throws CloudApiException, IOException
	{
		JsonObject body = withPluginAccountHash(new JsonObject(), accountHash);
		return requestAuthed("POST", "/me/trades/" + tradeId + "/ack-notify", body);
	}

	/** Parses the {@code inbox} array of a trade-inbox response, skipping malformed entries. */
	public List<TradeInboxItem> parseInbox(JsonObject response)
	{
		List<TradeInboxItem> out = new ArrayList<>();
		if (response == null || !response.has("inbox") || !response.get("inbox").isJsonArray())
		{
			return out;
		}
		for (JsonElement el : response.getAsJsonArray("inbox"))
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			String tradeId = text(o, "tradeId");
			if (tradeId == null)
			{
				continue;
			}
			String from = text(o, "fromDisplayName");
			out.add(new TradeInboxItem(tradeId, from == null ? "" : from, readBoolean(o, "notified")));
		}
		return out;
	}

	/**
	 * Persists {@code accessToken}/{@code refreshToken}/{@code accountId}/{@code status} from an auth
	 * response, binds them to {@code boundAccountHash}, and marks migrated if indicated.
	 */
	public void applyTokenResponse(JsonObject tokens, long boundAccountHash)
	{
		if (tokens == null)
		{
			return;
		}
		tokenStore.saveTokens(
			text(tokens, "accessToken"),
			text(tokens, "refreshToken"),
			text(tokens, "accountId"),
			text(tokens, "status"),
			boundAccountHash);
		if (textTrimmed(tokens, "migratedAt") != null || readBoolean(tokens, "migrated"))
		{
			tokenStore.setMigrated(true);
		}
	}

	/**
	 * Issues an authenticated request; on a 401 makes one attempt to refresh the access token and
	 * retries once before giving up.
	 */
	private JsonObject requestAuthed(String method, String pathAndQuery, JsonObject body)
		throws CloudApiException, IOException
	{
		try
		{
			return request(method, pathAndQuery, body, true);
		}
		catch (CloudApiException ex)
		{
			if (!ex.isUnauthorized() || !tryRefresh())
			{
				throw ex;
			}
			return request(method, pathAndQuery, body, true);
		}
	}

	/**
	 * Attempts a synchronous token refresh using the stored refresh token and current profile
	 * key hash. Clears stored credentials and invokes the stale-refresh handler if the refresh
	 * token itself is rejected as stale.
	 *
	 * @return true if the refresh succeeded and new tokens were applied.
	 */
	private boolean tryRefresh()
	{
		String refresh = tokenStore.getRefreshToken();
		String profileHash = profileKeyHasher.currentProfileKeyHash();
		long boundAccountHash = tokenStore.getBoundAccountHash();
		if (refresh == null || profileHash == null || boundAccountHash == -1L)
		{
			return false;
		}
		try
		{
			applyTokenResponse(refresh(refresh, profileHash), boundAccountHash);
			return true;
		}
		catch (CloudApiException e)
		{
			if (e.isStaleRefreshToken())
			{
				log.info("Clearing stale cloud credentials after refresh failure ({})", e.getCode());
				tokenStore.clear();
				Runnable handler = staleRefreshHandler;
				if (handler != null)
				{
					handler.run();
				}
			}
			else
			{
				log.warn("Cloud token refresh failed: {} {}", e.getCode(), e.getMessage());
			}
			return false;
		}
		catch (Exception e)
		{
			log.warn("Cloud token refresh failed", e);
			return false;
		}
	}

	/**
	 * Builds and executes a single HTTP request against the cloud API. Enforces the consent gate,
	 * attaches the bearer token when {@code authed}, serializes {@code body} as the JSON payload
	 * for non-GET methods, and throws {@link CloudApiException} on a non-2xx response.
	 */
	private JsonObject request(String method, String pathAndQuery, JsonObject body, boolean authed)
		throws CloudApiException, IOException
	{
		requireCloudConsentAllowed();
		Request.Builder b = new Request.Builder().url(requireApiUrl(pathAndQuery));
		if (authed)
		{
			String access = tokenStore.getAccessToken();
			if (access == null)
			{
				throw new CloudApiException(401, "missing_token", "Not signed in to cloud");
			}
			b.header("Authorization", "Bearer " + access);
		}
		if ("GET".equals(method))
		{
			b.get();
		}
		else
		{
			String payload = body == null ? "{}" : gson.toJson(body);
			b.method(method, RequestBody.create(JSON, payload));
		}

		try (Response response = http.newCall(b.build()).execute())
		{
			notifyActivitiesVersion(response.header("X-Activities-Version"));
			String text = readBody(response);
			JsonObject json = parseObject(text);
			if (!response.isSuccessful())
			{
				throw httpError(response.code(), text);
			}
			return json;
		}
	}

	/** Builds a {@link CloudApiException} from an error response and notifies the account-lock handler if applicable. */
	private CloudApiException httpError(int status, String body)
	{
		CloudApiException ex = exceptionFromHttpBody(status, body);
		if (ex.isAccountBanned() || ex.isAccountQuarantined())
		{
			Consumer<CloudApiException> handler = accountLockHandler;
			if (handler != null)
			{
				try
				{
					handler.accept(ex);
				}
				catch (RuntimeException handlerEx)
				{
					log.debug("Account lock handler failed", handlerEx);
				}
			}
		}
		return ex;
	}

	/** Parses an error response body's {@code error.code}/{@code error.message}/{@code credits} fields into an exception. */
	private static CloudApiException exceptionFromHttpBody(int status, String body)
	{
		String code = "http_error";
		String message = body == null ? "" : body;
		JsonObject json = parseObject(body);
		JsonObject err = objectOrEmpty(json, "error");
		String parsedCode = textTrimmed(err, "code");
		if (parsedCode != null)
		{
			code = parsedCode;
		}
		String parsedMessage = text(err, "message");
		if (parsedMessage != null && !parsedMessage.isBlank())
		{
			message = parsedMessage;
		}
		Long serverCredits = null;
		Double credits = readNumber(json, "credits");
		if (credits != null)
		{
			serverCredits = Math.max(0L, Math.round(credits));
		}
		return new CloudApiException(status, code, CloudHttpErrorMapper.humanize(status, code, message), serverCredits);
	}

	/** Forwards a non-blank {@code X-Activities-Version} header value to the registered listener, swallowing its errors. */
	private void notifyActivitiesVersion(String headerValue)
	{
		if (headerValue == null || headerValue.isBlank())
		{
			return;
		}
		Consumer<String> listener = activitiesVersionCb;
		if (listener != null)
		{
			try
			{
				listener.accept(headerValue.trim());
			}
			catch (Exception ex)
			{
				log.debug("Activities version listener failed", ex);
			}
		}
	}

	/** Removes the surrounding quotes from a raw ETag header value, if present. */
	private static String stripQuotes(String etag)
	{
		if (etag == null)
		{
			return "";
		}
		String t = etag.trim();
		if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\""))
		{
			return t.substring(1, t.length() - 1);
		}
		return t;
	}

	/** Resolves and parses the API URL for {@code pathAndQuery}, throwing if the configured base URL is invalid. */
	private HttpUrl requireApiUrl(String pathAndQuery) throws CloudApiException
	{
		HttpUrl url = HttpUrl.parse(CloudEndpoints.apiUrl(pathAndQuery));
		if (url == null)
		{
			throw new CloudApiException(0, "invalid_base_url", "Invalid API base URL: " + CloudEndpoints.API_BASE_URL);
		}
		return url;
	}

	/** Issues a GET, enforcing the consent gate and attaching an If-None-Match header when {@code cachedVersion} is set. */
	private Response getWithOptionalEtag(String path, String cachedVersion) throws CloudApiException, IOException
	{
		requireCloudConsentAllowed();
		Request.Builder b = new Request.Builder().url(requireApiUrl(path)).get();
		if (cachedVersion != null && !cachedVersion.isBlank())
		{
			String etag = cachedVersion.trim();
			if (!etag.startsWith("\""))
			{
				etag = "\"" + etag + "\"";
			}
			b.header("If-None-Match", etag);
		}
		return http.newCall(b.build()).execute();
	}

	/** Reads the response body, throwing {@link CloudApiException} if the response was not successful. */
	private String readSuccessfulBody(Response response) throws CloudApiException, IOException
	{
		String text = readBody(response);
		if (!response.isSuccessful())
		{
			throw httpError(response.code(), text);
		}
		return text;
	}

	/** Reads the response body as UTF-8 text, or {@code ""} if there is none. */
	private static String readBody(Response response) throws IOException
	{
		ResponseBody body = response.body();
		return body == null ? "" : new String(body.bytes(), StandardCharsets.UTF_8);
	}

	/** Parses {@code text} as a JSON object; returns an empty object for blank/invalid/non-object input. */
	private static JsonObject parseObject(String text)
	{
		if (text == null || text.isEmpty())
		{
			return new JsonObject();
		}
		try
		{
			JsonElement el = new JsonParser().parse(text);
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		}
		catch (Exception e)
		{
			return new JsonObject();
		}
	}
}

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
import static com.osrstcg.cloud.api.JsonObjects.text;
import com.osrstcg.cloud.session.ProfileKeyHasher;
import com.osrstcg.cloud.trade.TradeInboxItem;

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
	private volatile java.util.function.Consumer<CloudApiException> accountLockHandler;
	private volatile java.util.function.Consumer<String> activitiesVersionListener;
	/** Nesting depth for {@link #openConsentTraffic()} (create-profile after Yes). */
	private final ThreadLocal<Integer> consentTrafficDepth = ThreadLocal.withInitial(() -> 0);

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

	public void setStaleRefreshHandler(Runnable handler)
	{
		staleRefreshHandler = handler;
	}

	public void setAccountLockHandler(java.util.function.Consumer<CloudApiException> handler)
	{
		accountLockHandler = handler;
	}

	public void setActivitiesVersionListener(java.util.function.Consumer<String> listener)
	{
		activitiesVersionListener = listener;
	}

	public String getCachedCatalogVersion()
	{
		return cachedCatalogVersion;
	}

	public JsonObject getHealth() throws CloudApiException, IOException
	{
		JsonObject json = request("GET", "/health", null, false);
		cacheCatalogVersionFrom(json);
		return json;
	}

	public JsonObject getPacks() throws CloudApiException, IOException
	{
		JsonObject json = requestAuthed("GET", "/packs", null);
		cacheCatalogVersionFrom(json);
		return json;
	}

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
			String text = readBody(response);
			if (!response.isSuccessful())
			{
				throw httpError(response.code(), text);
			}
			JsonObject body = parseObject(text);
			String version = versionHeader;
			if (version == null || version.isBlank())
			{
				String etag = response.header("ETag");
				if (etag != null)
				{
					version = etag.replace("\"", "").trim();
				}
			}
			if (version != null && !version.isBlank())
			{
				setCachedCatalogVersion(version);
			}
			return LiveCardsResponse.ok(body, text, version);
		}
	}

	public JsonObject getPublicPlayerStats(String displayName) throws CloudApiException, IOException
	{
		String name = displayName == null ? "" : displayName.trim();
		String slug = name.replace(' ', '_');
		String encoded = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
		return request("GET", "/players/" + encoded + "/stats", null, false);
	}

	public void setCachedCatalogVersion(String catalogVersion)
	{
		if (catalogVersion != null && !catalogVersion.isBlank())
		{
			cachedCatalogVersion = catalogVersion.trim();
		}
	}

	private void cacheCatalogVersionFrom(JsonObject json)
	{
		if (json != null && json.has("catalogVersion") && !json.get("catalogVersion").isJsonNull())
		{
			cachedCatalogVersion = json.get("catalogVersion").getAsString();
		}
	}

	public JsonObject pairStart(String displayName, String profileKeyHash, long accountHash)
		throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("displayName", displayName);
		body.addProperty("profileKeyHash", profileKeyHash);
		body.addProperty("accountHash", Long.toString(accountHash));
		return request("POST", "/auth/pair/start", body, false);
	}

	public JsonObject refresh(String refreshToken, String profileKeyHash) throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("refreshToken", refreshToken);
		body.addProperty("profileKeyHash", profileKeyHash);
		return request("POST", "/auth/refresh", body, false);
	}

	public JsonObject webCode(String next) throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		if (next != null && !next.isBlank())
		{
			body.addProperty("next", next.trim());
		}
		return requestAuthed("POST", "/auth/web-code", body);
	}

	public JsonObject getStats() throws CloudApiException, IOException
	{
		return requestAuthed("GET", "/me/stats", null);
	}

	public JsonObject getState() throws CloudApiException, IOException
	{
		return requestAuthed("GET", "/me/state", null);
	}

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

	public JsonObject attest(JsonObject body) throws CloudApiException, IOException
	{
		return requestAuthed("POST", "/credits/attest", body);
	}

	public JsonObject settleHiscores(String displayName, long accountHash, boolean snapshot)
		throws CloudApiException, IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("displayName", displayName);
		body.addProperty("accountHash", Long.toString(accountHash));
		if (snapshot)
		{
			body.addProperty("snapshot", true);
		}
		return requestAuthed("POST", "/credits/settle-hiscores", body);
	}

	public JsonObject settleHiscores(String displayName, long accountHash) throws CloudApiException, IOException
	{
		return settleHiscores(displayName, accountHash, false);
	}

	public String getActivitiesVersion() throws CloudApiException, IOException
	{
		JsonObject json = request("GET", "/config/activities/version", null, false);
		if (json.has("version") && !json.get("version").isJsonNull())
		{
			return json.get("version").getAsString();
		}
		return "";
	}

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

	public JsonObject openPack(JsonObject body) throws CloudApiException, IOException
	{
		return requestAuthed("POST", "/packs/open", body);
	}

	public String resolvePublicUrl(String pathOrUrl)
	{
		return CloudEndpoints.resolvePublicUrl(pathOrUrl);
	}

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

	public JsonObject createTrade(String partnerDisplayName, long accountHash) throws CloudApiException, IOException
	{
		JsonObject body = withPluginAccountHash(new JsonObject(), accountHash);
		body.addProperty("partnerDisplayName", partnerDisplayName);
		return requestAuthed("POST", "/trades", body);
	}

	public JsonObject cancelTrade(String tradeId, long accountHash) throws CloudApiException, IOException
	{
		JsonObject body = withPluginAccountHash(new JsonObject(), accountHash);
		return requestAuthed("POST", "/trades/" + tradeId + "/cancel", body);
	}

	public JsonObject getTradeInbox(long accountHash) throws CloudApiException, IOException
	{
		return getTradeInbox(accountHash, null);
	}

	public JsonObject getTradeInbox(long accountHash, Long sinceRevision) throws CloudApiException, IOException
	{
		String path = "/me/trades/inbox?accountHash=" + accountHash;
		if (sinceRevision != null)
		{
			path += "&sinceRevision=" + sinceRevision;
		}
		return requestAuthed("GET", path, null);
	}

	public JsonObject ackTradeNotify(String tradeId, long accountHash) throws CloudApiException, IOException
	{
		JsonObject body = withPluginAccountHash(new JsonObject(), accountHash);
		return requestAuthed("POST", "/me/trades/" + tradeId + "/ack-notify", body);
	}

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
			if (!o.has("tradeId"))
			{
				continue;
			}
			out.add(new TradeInboxItem(
				o.get("tradeId").getAsString(),
				o.has("fromDisplayName") ? o.get("fromDisplayName").getAsString() : "",
				o.has("notified") && o.get("notified").getAsBoolean()));
		}
		return out;
	}

	public void applyTokenResponse(JsonObject tokens)
	{
		if (tokens == null)
		{
			return;
		}
		tokenStore.saveTokens(
			text(tokens, "accessToken"),
			text(tokens, "refreshToken"),
			text(tokens, "accountId"),
			text(tokens, "status"));
		if (tokens.has("migratedAt") && !tokens.get("migratedAt").isJsonNull()
			&& !tokens.get("migratedAt").getAsString().isBlank())
		{
			tokenStore.setMigrated(true);
		}
		else if (tokens.has("migrated") && !tokens.get("migrated").isJsonNull()
			&& tokens.get("migrated").getAsBoolean())
		{
			tokenStore.setMigrated(true);
		}
	}

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

	private boolean tryRefresh()
	{
		String refresh = tokenStore.getRefreshToken();
		String profileHash = profileKeyHasher.currentProfileKeyHash();
		if (refresh == null || profileHash == null)
		{
			return false;
		}
		try
		{
			applyTokenResponse(refresh(refresh, profileHash));
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

	private JsonObject request(String method, String pathAndQuery, JsonObject body, boolean authed)
		throws CloudApiException, IOException
	{
		requireCloudConsentAllowed();
		HttpUrl url = HttpUrl.parse(CloudEndpoints.apiUrl(pathAndQuery));
		if (url == null)
		{
			throw new CloudApiException(0, "invalid_base_url", "Invalid API base URL: " + CloudEndpoints.API_BASE_URL);
		}

		Request.Builder b = new Request.Builder().url(url);
		if (authed)
		{
			String access = tokenStore.getAccessToken();
			if (access == null)
			{
				throw new CloudApiException(401, "missing_token", "Not signed in to cloud");
			}
			b.header("Authorization", "Bearer " + access);
		}
		if ("GET".equals(method) || "HEAD".equals(method))
		{
			b.method(method, null);
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

	private CloudApiException httpError(int status, String body)
	{
		CloudApiException ex = exceptionFromHttpBody(status, body);
		if (ex.isAccountBanned() || ex.isAccountQuarantined())
		{
			java.util.function.Consumer<CloudApiException> handler = accountLockHandler;
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

	private static CloudApiException exceptionFromHttpBody(int status, String body)
	{
		String code = "http_error";
		String message = body == null ? "" : body;
		Long serverCredits = null;
		JsonObject json = parseObject(body);
		if (json.has("error") && json.get("error").isJsonObject())
		{
			JsonObject err = json.getAsJsonObject("error");
			if (err.has("code") && !err.get("code").isJsonNull())
			{
				String parsedCode = err.get("code").getAsString();
				if (parsedCode != null && !parsedCode.isBlank())
				{
					code = parsedCode.trim();
				}
			}
			if (err.has("message") && !err.get("message").isJsonNull())
			{
				String parsedMessage = err.get("message").getAsString();
				if (parsedMessage != null && !parsedMessage.isBlank())
				{
					message = parsedMessage;
				}
			}
		}
		if (json.has("credits") && !json.get("credits").isJsonNull() && json.get("credits").isJsonPrimitive())
		{
			try
			{
				serverCredits = json.get("credits").getAsLong();
			}
			catch (RuntimeException ignored)
			{
				// leave null
			}
		}
		return new CloudApiException(status, code, CloudHttpErrorMapper.humanize(status, code, message), serverCredits);
	}

	private void notifyActivitiesVersion(String headerValue)
	{
		if (headerValue == null || headerValue.isBlank())
		{
			return;
		}
		java.util.function.Consumer<String> listener = activitiesVersionListener;
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

	private Response getWithOptionalEtag(String path, String cachedVersion) throws CloudApiException, IOException
	{
		requireCloudConsentAllowed();
		HttpUrl url = HttpUrl.parse(CloudEndpoints.apiUrl(path));
		if (url == null)
		{
			throw new CloudApiException(0, "invalid_base_url", "Invalid API base URL: " + CloudEndpoints.API_BASE_URL);
		}
		Request.Builder b = new Request.Builder().url(url).get();
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

	private static String readBody(Response response) throws IOException
	{
		ResponseBody body = response.body();
		return body == null ? "" : new String(body.bytes(), StandardCharsets.UTF_8);
	}

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

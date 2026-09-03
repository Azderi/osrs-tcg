package com.osrstcg.cloud.catalog;

import com.google.gson.JsonObject;

/** Result of {@code GET /api/v1/catalog/cards/live}. */
public final class LiveCardsResponse
{
	private final boolean notModified;
	private final JsonObject body;
	private final String rawJson;
	private final String catalogVersion;

	private LiveCardsResponse(boolean notModified, JsonObject body, String rawJson, String catalogVersion)
	{
		this.notModified = notModified;
		this.body = body;
		this.rawJson = rawJson;
		this.catalogVersion = catalogVersion == null ? "" : catalogVersion;
	}

	/** Builds a result for a 304 response; the caller should keep using its previously cached catalog. */
	public static LiveCardsResponse notModified(String catalogVersion)
	{
		return new LiveCardsResponse(true, null, null, catalogVersion);
	}

	/** Builds a result for a 200 response carrying a fresh catalog body. */
	public static LiveCardsResponse ok(JsonObject body, String rawJson, String catalogVersion)
	{
		return new LiveCardsResponse(false, body, rawJson, catalogVersion);
	}

	/** True when the server returned 304 (client's cached version is still current). */
	public boolean isNotModified()
	{
		return notModified;
	}

	/** Parsed response body; null when {@link #isNotModified()}. */
	public JsonObject getBody()
	{
		return body;
	}

	/** Raw response text as received, for disk caching; null when {@link #isNotModified()}. */
	public String getRawJson()
	{
		return rawJson;
	}

	/** Catalog version from the response headers/body; never null (may be empty). */
	public String getCatalogVersion()
	{
		return catalogVersion;
	}
}

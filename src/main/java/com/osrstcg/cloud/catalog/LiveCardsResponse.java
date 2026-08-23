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

	public static LiveCardsResponse notModified(String catalogVersion)
	{
		return new LiveCardsResponse(true, null, null, catalogVersion);
	}

	public static LiveCardsResponse ok(JsonObject body, String rawJson, String catalogVersion)
	{
		return new LiveCardsResponse(false, body, rawJson, catalogVersion);
	}

	public boolean isNotModified()
	{
		return notModified;
	}

	public JsonObject getBody()
	{
		return body;
	}

	public String getRawJson()
	{
		return rawJson;
	}

	public String getCatalogVersion()
	{
		return catalogVersion;
	}
}

package com.osrstcg.cloud.api;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.trade.TradeCloudService;
import com.osrstcg.state.TcgStateService;

/** Applies the {@code revision}/{@code stateHash} fields present on many cloud API responses to local sync state. */
public final class CloudResponseSync
{
	private CloudResponseSync()
	{
	}

	/** No-op when {@code response} lacks a {@code revision} field; otherwise records it on both services. */
	public static void applyRevision(JsonObject response, TcgStateService stateService, TradeCloudService tradeCloud)
	{
		if (response == null || !response.has("revision") || response.get("revision").isJsonNull())
		{
			return;
		}
		long revision = response.get("revision").getAsLong();
		String stateHash = JsonObjects.text(response, "stateHash");
		stateService.applyCloudSyncMarkers(revision, stateHash == null ? "" : stateHash);
		tradeCloud.noteRevision(revision);
	}
}

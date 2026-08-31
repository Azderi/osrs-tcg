package com.osrstcg.cloud.api;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.trade.TradeCloudService;
import com.osrstcg.state.TcgStateService;

public final class CloudResponseSync
{
	private CloudResponseSync()
	{
	}

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

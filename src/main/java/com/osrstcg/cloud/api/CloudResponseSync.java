package com.osrstcg.cloud.api;

import com.google.gson.JsonObject;
import com.osrstcg.cloud.trade.TradeCloudService;
import java.util.function.Consumer;
/** Applies economy/revision fields from cloud API responses to local sync collaborators. */
public final class CloudResponseSync
{
	private CloudResponseSync()
	{
	}
/**
	 * Applies sidebar economy fields when present, then notes {@code revision} on {@code tradeCloud}
	 * only (no {@code stateHash} write).
	 *
	 * @return true if economy fields were applied
	 */
	public static boolean applyEconomyAndRevision(
		JsonObject response,
		Consumer<JsonObject> applySidebarStats,
		TradeCloudService tradeCloud)
	{
		boolean appliedEconomy = applyEconomyFields(response, applySidebarStats);
		noteRevisionIfPresent(response, tradeCloud);
		return appliedEconomy;
	}
/** True when {@code response} carries a non-null credits / openedPacks / totalCreditsGained. */
	public static boolean hasEconomyFields(JsonObject response)
	{
		return response != null
			&& (hasNonNullField(response, "credits")
				|| hasNonNullField(response, "openedPacks")
				|| hasNonNullField(response, "totalCreditsGained"));
	}
/** True when {@code key} is present and not JSON null. */
	private static boolean hasNonNullField(JsonObject response, String key)
	{
		return response.has(key) && !response.get(key).isJsonNull();
	}
/** Applies sidebar stats when economy fields are present; returns whether they were applied. */
	public static boolean applyEconomyFields(JsonObject response, Consumer<JsonObject> applySidebarStats)
	{
		if (!hasEconomyFields(response) || applySidebarStats == null)
		{
			return false;
		}
		applySidebarStats.accept(response);
		return true;
	}
/** Notes {@code revision} on {@code tradeCloud} when the field is a number. */
	public static void noteRevisionIfPresent(JsonObject response, TradeCloudService tradeCloud)
	{
		if (response == null || tradeCloud == null)
		{
			return;
		}
		Double revision = JsonObjects.readNumber(response, "revision");
		if (revision != null)
		{
			tradeCloud.noteRevision(Math.round(revision));
		}
	}
}

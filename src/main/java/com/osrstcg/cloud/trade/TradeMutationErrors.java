package com.osrstcg.cloud.trade;

import com.osrstcg.cloud.api.CloudApiException;
/**
 * Chat-facing messages for hardened trade mutator failures ({@code accountHash} / eligibility).
 */
final class TradeMutationErrors
{
/** Static-only utility class; not instantiable. */
	private TradeMutationErrors()
	{
	}
/** @return player-facing body after {@code [OSRS TCG] }, or {@code null} to use a generic fallback */
	static String messageFor(CloudApiException ex)
	{
		if (ex == null)
		{
			return null;
		}
		String code = ex.getCode() == null ? "" : ex.getCode();
		if ("missing_account_hash".equals(code) || "invalid_account_hash".equals(code))
		{
			return "Trade failed: account hash missing - try relogging.";
		}
		if ("account_hash_mismatch".equals(code) || "account_hash_unbound".equals(code))
		{
			return "Trade failed: account hash mismatch - try relogging or re-pairing cloud.";
		}
		if ("not_trade_eligible".equals(code) || "quarantined".equals(code) || "banned".equals(code)
			|| "account_banned".equals(code))
		{
			return "Your account cannot trade right now.";
		}
		if ("partner_not_trade_eligible".equals(code))
		{
			return "That player cannot trade right now.";
		}
		if (ex.isRateLimited() || "rate_limited".equals(code))
		{
			return "Trade actions are rate-limited - try again shortly.";
		}
		if (ex.getStatus() == 404 || "partner_not_found".equals(code))
		{
			return "This player doesn't have a public collection.";
		}
		String serverMsg = ex.getMessage();
		if (serverMsg != null && !serverMsg.isBlank() && !serverMsg.equals(code))
		{
			return "Trade failed: " + serverMsg.trim();
		}
		return null;
	}
}

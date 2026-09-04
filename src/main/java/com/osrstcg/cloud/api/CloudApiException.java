package com.osrstcg.cloud.api;
/** Thrown by {@link CloudApiClient} for any failed cloud HTTP call (non-2xx response or transport-level rejection). */
public final class CloudApiException extends Exception
{
	private final int status;
	private final String code;
/** Optional server credits from an error body (e.g. insufficient funds on pack open). */
	private final Long serverCredits;
/** Creates an exception with no server-reported credits balance. */
	public CloudApiException(int status, String code, String message)
	{
		this(status, code, message, null);
	}
/**
	 * @param status HTTP status code, or 0 for a non-HTTP failure (e.g. invalid base URL).
	 * @param code server error code, defaulted to {@code "error"} when null.
	 * @param message human-facing message; falls back to {@code code} when null.
	 */
	public CloudApiException(int status, String code, String message, Long serverCredits)
	{
		super(message == null ? code : message);
		this.status = status;
		this.code = code == null ? "error" : code;
		this.serverCredits = serverCredits;
	}
/** HTTP status code, or 0 for a non-HTTP failure. */
	public int getStatus()
	{
		return status;
	}
/** Server error code (never null; defaults to {@code "error"}). */
	public String getCode()
	{
		return code;
	}
/** Server-reported credits when present on the error JSON; otherwise null. */
	public Long getServerCredits()
	{
		return serverCredits;
	}
/** True for HTTP 401 (not signed in / expired token). */
	public boolean isUnauthorized()
	{
		return status == 401;
	}
/** True for HTTP 429 (client should back off and retry later). */
	public boolean isRateLimited()
	{
		return status == 429;
	}
/** True for any 5xx response. */
	public boolean isServerError()
	{
		return status >= 500 && status < 600;
	}
/** True when the server rejected the request due to a stale local catalog version. */
	public boolean isCatalogMismatch()
	{
		return "catalog_mismatch".equals(code);
	}
/** True when the account is permanently banned. */
	public boolean isAccountBanned()
	{
		return "banned".equalsIgnoreCase(code) || "account_banned".equalsIgnoreCase(code);
	}
/** True when the account is temporarily quarantined. */
	public boolean isAccountQuarantined()
	{
		return "quarantined".equalsIgnoreCase(code);
	}
/** True when the refresh token itself is invalid/stale, meaning stored credentials should be cleared. */
	public boolean isStaleRefreshToken()
	{
		return "invalid_refresh_token".equals(code) || "profile_mismatch".equals(code);
	}
/** True when the failure is due to the account not having enough credits, inferred from code or message. */
	public boolean isInsufficientCredits()
	{
		String normalizedCode = code == null ? "" : code.trim().toLowerCase();
		if (normalizedCode.contains("insufficient")
			|| normalizedCode.contains("not_enough")
			|| "payment_required".equals(normalizedCode)
			|| "insufficient_credits".equals(normalizedCode))
		{
			return true;
		}
		String message = getMessage() == null ? "" : getMessage().toLowerCase();
		return message.contains("not enough credit")
			|| message.contains("insufficient credit");
	}
}

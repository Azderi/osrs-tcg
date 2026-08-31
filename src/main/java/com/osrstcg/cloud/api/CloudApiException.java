package com.osrstcg.cloud.api;

public final class CloudApiException extends Exception
{
	private final int status;
	private final String code;
	/** Optional server credits from an error body (e.g. insufficient funds on pack open). */
	private final Long serverCredits;

	public CloudApiException(int status, String code, String message)
	{
		this(status, code, message, null);
	}

	public CloudApiException(int status, String code, String message, Long serverCredits)
	{
		super(message == null ? code : message);
		this.status = status;
		this.code = code == null ? "error" : code;
		this.serverCredits = serverCredits;
	}

	public int getStatus()
	{
		return status;
	}

	public String getCode()
	{
		return code;
	}

	/** Server-reported credits when present on the error JSON; otherwise null. */
	public Long getServerCredits()
	{
		return serverCredits;
	}

	public boolean isUnauthorized()
	{
		return status == 401;
	}

	public boolean isRateLimited()
	{
		return status == 429;
	}

	public boolean isServerError()
	{
		return status >= 500 && status < 600;
	}

	public boolean isCatalogMismatch()
	{
		return "catalog_mismatch".equals(code);
	}

	public boolean isAccountBanned()
	{
		return "banned".equalsIgnoreCase(code) || "account_banned".equalsIgnoreCase(code);
	}

	public boolean isAccountQuarantined()
	{
		return "quarantined".equalsIgnoreCase(code);
	}

	public boolean isStaleRefreshToken()
	{
		return "invalid_refresh_token".equals(code) || "profile_mismatch".equals(code);
	}

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

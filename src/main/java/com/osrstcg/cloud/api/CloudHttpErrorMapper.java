package com.osrstcg.cloud.api;
/** Maps HTTP status / gateway HTML bodies to player-facing CloudApiException messages. */
final class CloudHttpErrorMapper
{
	private CloudHttpErrorMapper()
	{
	}
/**
	 * Produces a short player-facing message: a canned message for rate limiting or an
	 * HTML/gateway body, the trimmed/truncated server message otherwise, or a status-based
	 * default when the message is blank.
	 */
	static String humanize(int status, String code, String message)
	{
		if (status == 429 || "rate_limited".equals(code))
		{
			return "Too many requests - try again in a moment.";
		}
		String cleaned = message == null ? "" : message.trim();
		if (cleaned.isEmpty() || looksLikeHtmlOrGatewayPage(cleaned))
		{
			return defaultMessageForHttpStatus(status);
		}
		cleaned = cleaned.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").trim();
		if (cleaned.length() > 160)
		{
			cleaned = cleaned.substring(0, 157) + "...";
		}
		return cleaned;
	}
/** True when {@code text} looks like an HTML error page (e.g. from an nginx gateway) rather than API JSON. */
	static boolean looksLikeHtmlOrGatewayPage(String text)
	{
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		return lower.startsWith("<!doctype")
			|| lower.startsWith("<html")
			|| lower.contains("<head>")
			|| lower.contains("<title>")
			|| lower.contains("nginx/");
	}
/** Generic player-facing message for an HTTP status when no usable server message is available. */
	static String defaultMessageForHttpStatus(int status)
	{
		if (status == 401 || status == 403)
		{
			return "Not authorized.";
		}
		if (status == 404)
		{
			return "Not found.";
		}
		if (status == 408 || status == 504)
		{
			return "Request timed out - try again.";
		}
		if (status == 502 || status == 503)
		{
			return "Cloud unavailable - try relogging in a few minutes.";
		}
		if (status >= 500)
		{
			return "Cloud server error (" + status + ").";
		}
		if (status > 0)
		{
			return "Request failed (HTTP " + status + ").";
		}
		return "Request failed.";
	}
}

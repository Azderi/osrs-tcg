package com.osrstcg.cloud.session;

import com.osrstcg.cloud.api.JsonObjects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/** Session JWT store (access/refresh). Not account credentials. */
@Singleton
public final class CloudTokenStore
{
	private static final String GROUP = "osrstcg";
	private static final String ACCESS = "cloudAccessToken";
	private static final String REFRESH = "cloudRefreshToken";
	private static final String ACCOUNT_ID = "cloudAccountId";
	private static final String BOUND_ACCOUNT_HASH = "cloudBoundAccountHash";
	private static final String MIGRATED = "cloudMigrated";
	private static final String STATUS = "cloudAccountStatus";

	private final ConfigManager configManager;

	@Inject
	CloudTokenStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** Returns the stored access token, or {@code null} if none is set. */
	public String getAccessToken()
	{
		return JsonObjects.blankToNull(configManager.getRSProfileConfiguration(GROUP, ACCESS));
	}

	/** Returns the stored refresh token, or {@code null} if none is set. */
	public String getRefreshToken()
	{
		return JsonObjects.blankToNull(configManager.getRSProfileConfiguration(GROUP, REFRESH));
	}

	/**
	 * Jagex account hash these tokens were issued for, or {@code -1} if unset/unparseable.
	 * Used to refuse refresh when a different account is logged in on the same RS profile.
	 */
	public long getBoundAccountHash()
	{
		return parseBoundAccountHash(configManager.getRSProfileConfiguration(GROUP, BOUND_ACCOUNT_HASH));
	}

	/** Whether stored tokens are bound to {@code accountHash} (and that hash is valid). */
	public boolean tokensBoundTo(long accountHash)
	{
		return isBoundTo(getBoundAccountHash(), accountHash);
	}

	/**
	 * Whether existing credentials must be cleared before connecting as {@code liveAccountHash}.
	 * Unbound legacy tokens ({@code boundAccountHash == -1}) also force a re-pair.
	 */
	static boolean shouldClearForAccount(long boundAccountHash, boolean hasRefreshToken, long liveAccountHash)
	{
		if (!hasRefreshToken || liveAccountHash == -1L)
		{
			return false;
		}
		return !isBoundTo(boundAccountHash, liveAccountHash);
	}

	static boolean isBoundTo(long boundAccountHash, long accountHash)
	{
		if (accountHash == -1L)
		{
			return false;
		}
		return boundAccountHash == accountHash;
	}

	/** Parses a stored bound-hash string; {@code -1} if null/blank/unparseable. */
	static long parseBoundAccountHash(String raw)
	{
		String value = JsonObjects.blankToNull(raw);
		if (value == null)
		{
			return -1L;
		}
		try
		{
			return Long.parseLong(value);
		}
		catch (NumberFormatException ex)
		{
			return -1L;
		}
	}

	/** Whether this profile has completed the one-time cloud migration/consent flow. */
	public boolean isMigrated()
	{
		return "true".equalsIgnoreCase(configManager.getRSProfileConfiguration(GROUP, MIGRATED));
	}

	/**
	 * Persists the access/refresh token pair, optional account id/status, and the Jagex account
	 * hash these tokens belong to. No-op if either token is null/empty or {@code boundAccountHash}
	 * is {@code -1}.
	 */
	public void saveTokens(
		String accessToken,
		String refreshToken,
		String accountId,
		String status,
		long boundAccountHash)
	{
		if (accessToken == null || accessToken.isEmpty() || refreshToken == null || refreshToken.isEmpty())
		{
			return;
		}
		if (boundAccountHash == -1L)
		{
			return;
		}
		configManager.setRSProfileConfiguration(GROUP, ACCESS, accessToken);
		configManager.setRSProfileConfiguration(GROUP, REFRESH, refreshToken);
		configManager.setRSProfileConfiguration(GROUP, BOUND_ACCOUNT_HASH, Long.toString(boundAccountHash));
		if (accountId != null && !accountId.isEmpty())
		{
			configManager.setRSProfileConfiguration(GROUP, ACCOUNT_ID, accountId);
		}
		if (status != null && !status.isEmpty())
		{
			configManager.setRSProfileConfiguration(GROUP, STATUS, status);
		}
	}

	/** Marks (or unmarks) this profile as having completed cloud migration/consent. */
	public void setMigrated(boolean migrated)
	{
		configManager.setRSProfileConfiguration(GROUP, MIGRATED, migrated ? "true" : "false");
	}

	/** Persists the account status (e.g. banned/quarantined). No-op if {@code status} is null/empty. */
	public void setAccountStatus(String status)
	{
		if (status == null || status.isEmpty())
		{
			return;
		}
		configManager.setRSProfileConfiguration(GROUP, STATUS, status);
	}

	/** Removes stored access/refresh tokens, account id, bound hash, and status (does not clear the migrated flag). */
	public void clear()
	{
		configManager.unsetRSProfileConfiguration(GROUP, ACCESS);
		configManager.unsetRSProfileConfiguration(GROUP, REFRESH);
		configManager.unsetRSProfileConfiguration(GROUP, ACCOUNT_ID);
		configManager.unsetRSProfileConfiguration(GROUP, BOUND_ACCOUNT_HASH);
		configManager.unsetRSProfileConfiguration(GROUP, STATUS);
	}

	/** Whether a refresh token is currently stored. */
	public boolean hasRefreshToken()
	{
		return getRefreshToken() != null;
	}
}

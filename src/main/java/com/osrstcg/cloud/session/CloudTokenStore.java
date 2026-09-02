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

	/** Whether this profile has completed the one-time cloud migration/consent flow. */
	public boolean isMigrated()
	{
		return "true".equalsIgnoreCase(configManager.getRSProfileConfiguration(GROUP, MIGRATED));
	}

	/**
	 * Persists the access/refresh token pair, and optionally the account id and status.
	 * No-op if either token is null/empty.
	 */
	public void saveTokens(String accessToken, String refreshToken, String accountId, String status)
	{
		if (accessToken == null || accessToken.isEmpty() || refreshToken == null || refreshToken.isEmpty())
		{
			return;
		}
		configManager.setRSProfileConfiguration(GROUP, ACCESS, accessToken);
		configManager.setRSProfileConfiguration(GROUP, REFRESH, refreshToken);
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

	/** Removes stored access/refresh tokens, account id, and status (does not clear the migrated flag). */
	public void clear()
	{
		configManager.unsetRSProfileConfiguration(GROUP, ACCESS);
		configManager.unsetRSProfileConfiguration(GROUP, REFRESH);
		configManager.unsetRSProfileConfiguration(GROUP, ACCOUNT_ID);
		configManager.unsetRSProfileConfiguration(GROUP, STATUS);
	}

	/** Whether a refresh token is currently stored. */
	public boolean hasRefreshToken()
	{
		return getRefreshToken() != null;
	}
}

package com.osrstcg.cloud.session;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * RSProfile-scoped cloud session secrets (not exposed as {@code @ConfigItem}s).
 */
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

	public String getAccessToken()
	{
		return blankToNull(configManager.getRSProfileConfiguration(GROUP, ACCESS));
	}

	public String getRefreshToken()
	{
		return blankToNull(configManager.getRSProfileConfiguration(GROUP, REFRESH));
	}

	public boolean isMigrated()
	{
		return "true".equalsIgnoreCase(configManager.getRSProfileConfiguration(GROUP, MIGRATED));
	}

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

	public void setMigrated(boolean migrated)
	{
		configManager.setRSProfileConfiguration(GROUP, MIGRATED, migrated ? "true" : "false");
	}

	public void setAccountStatus(String status)
	{
		if (status == null || status.isEmpty())
		{
			return;
		}
		configManager.setRSProfileConfiguration(GROUP, STATUS, status);
	}

	public void clear()
	{
		configManager.unsetRSProfileConfiguration(GROUP, ACCESS);
		configManager.unsetRSProfileConfiguration(GROUP, REFRESH);
		configManager.unsetRSProfileConfiguration(GROUP, ACCOUNT_ID);
		configManager.unsetRSProfileConfiguration(GROUP, STATUS);
		// Keep cloudMigrated - account migration survives token revocation / re-pair on this profile.
	}

	public boolean hasRefreshToken()
	{
		return getRefreshToken() != null;
	}

	private static String blankToNull(String value)
	{
		if (value == null || value.trim().isEmpty())
		{
			return null;
		}
		return value;
	}
}

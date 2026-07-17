package com.osrstcg.persist;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Profile-scoped share credentials (write token is secret — not exposed as a {@code @ConfigItem}).
 */
@Singleton
public class CollectionShareCredentialsStore
{
	private static final String GROUP = "osrstcg";
	private static final String SHARE_ID_KEY = "webShareId";
	private static final String WRITE_TOKEN_KEY = "webShareWriteToken";

	private final ConfigManager configManager;

	@Inject
	public CollectionShareCredentialsStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	public String getShareId()
	{
		return blankToNull(configManager.getRSProfileConfiguration(GROUP, SHARE_ID_KEY));
	}

	public String getWriteToken()
	{
		return blankToNull(configManager.getRSProfileConfiguration(GROUP, WRITE_TOKEN_KEY));
	}

	public boolean hasCredentials()
	{
		return getShareId() != null && getWriteToken() != null;
	}

	public void save(String shareId, String writeToken)
	{
		if (shareId == null || shareId.isEmpty() || writeToken == null || writeToken.isEmpty())
		{
			return;
		}
		configManager.setRSProfileConfiguration(GROUP, SHARE_ID_KEY, shareId);
		configManager.setRSProfileConfiguration(GROUP, WRITE_TOKEN_KEY, writeToken);
	}

	public void clear()
	{
		configManager.unsetRSProfileConfiguration(GROUP, SHARE_ID_KEY);
		configManager.unsetRSProfileConfiguration(GROUP, WRITE_TOKEN_KEY);
	}

	private static String blankToNull(String value)
	{
		if (value == null)
		{
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}

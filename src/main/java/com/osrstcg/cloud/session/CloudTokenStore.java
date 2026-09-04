package com.osrstcg.cloud.session;

import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.cloud.session.CloudSessionFileStore.SessionData;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;

/** Session JWT store (access/refresh). Not account credentials. Consent stays in RS-profile config. */
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
	private final Client client;
	private final CloudSessionFileStore sessionFileStore;

	private volatile long lastKnownAccountHash = -1L;
	private volatile long cachedAccountHash = -1L;
	private volatile SessionData cached;

	@Inject
	CloudTokenStore(ConfigManager configManager, Client client, CloudSessionFileStore sessionFileStore)
	{
		this.configManager = configManager;
		this.client = client;
		this.sessionFileStore = sessionFileStore;
	}

	/** Returns the stored access token, or {@code null} if none is set. */
	public String getAccessToken()
	{
		SessionData data = session();
		return data == null ? null : JsonObjects.blankToNull(data.accessToken);
	}

	/** Returns the stored refresh token, or {@code null} if none is set. */
	public String getRefreshToken()
	{
		SessionData data = session();
		return data == null ? null : JsonObjects.blankToNull(data.refreshToken);
	}

	/**
	 * Jagex account hash these tokens were issued for, or {@code -1} if unset/unparseable.
	 * Used to refuse refresh when a different account is logged in on the same RS profile.
	 */
	public long getBoundAccountHash()
	{
		SessionData data = session();
		if (data == null)
		{
			return -1L;
		}
		return data.boundAccountHash != 0L ? data.boundAccountHash : -1L;
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
		lastKnownAccountHash = boundAccountHash;
		SessionData data = new SessionData();
		data.accessToken = accessToken;
		data.refreshToken = refreshToken;
		data.accountId = accountId;
		data.status = status;
		data.boundAccountHash = boundAccountHash;
		sessionFileStore.save(boundAccountHash, data);
		cachedAccountHash = boundAccountHash;
		cached = data;
		unsetLegacyTokenKeys();
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
		SessionData data = session();
		long hash = resolveAccountHash();
		if (data == null || hash == -1L)
		{
			return;
		}
		data.status = status;
		sessionFileStore.save(hash, data);
		cachedAccountHash = hash;
		cached = data;
	}

	/** Removes stored access/refresh tokens, account id, bound hash, and status (does not clear the migrated flag). */
	public void clear()
	{
		long hash = resolveAccountHash();
		if (hash != -1L)
		{
			sessionFileStore.delete(hash);
		}
		cachedAccountHash = -1L;
		cached = null;
		unsetLegacyTokenKeys();
	}

	/** Deletes the entire on-disk profile folder for the current account (save + session). */
	public void wipeAccountProfileDir()
	{
		long hash = resolveAccountHash();
		if (hash != -1L)
		{
			sessionFileStore.deleteAccountDir(hash);
		}
		cachedAccountHash = -1L;
		cached = null;
		unsetLegacyTokenKeys();
	}

	/** Whether a refresh token is currently stored. */
	public boolean hasRefreshToken()
	{
		return getRefreshToken() != null;
	}

	private SessionData session()
	{
		long hash = resolveAccountHash();
		if (hash == -1L)
		{
			return null;
		}
		if (cachedAccountHash == hash && cached != null)
		{
			if (shouldClearForAccount(cached.boundAccountHash, !blank(cached.refreshToken), hash))
			{
				clear();
				return null;
			}
			return cached;
		}
		migrateLegacyTokensIfNeeded(hash);
		SessionData loaded = sessionFileStore.load(hash);
		if (loaded != null && shouldClearForAccount(loaded.boundAccountHash, !blank(loaded.refreshToken), hash))
		{
			sessionFileStore.delete(hash);
			cachedAccountHash = hash;
			cached = null;
			unsetLegacyTokenKeys();
			return null;
		}
		if (loaded != null && loaded.boundAccountHash == -1L)
		{
			loaded.boundAccountHash = hash;
		}
		cachedAccountHash = hash;
		cached = loaded;
		return loaded;
	}

	private long resolveAccountHash()
	{
		long hash = client.getAccountHash();
		if (hash != -1L)
		{
			lastKnownAccountHash = hash;
			return hash;
		}
		return lastKnownAccountHash;
	}

	/**
	 * One-time move of RS-profile token keys into {@code cloud-session.json} for the live account.
	 * Mismatched bound hash → drop legacy keys (force re-pair). Never moves {@code cloudMigrated}.
	 */
	private void migrateLegacyTokensIfNeeded(long liveAccountHash)
	{
		String access = JsonObjects.blankToNull(configManager.getRSProfileConfiguration(GROUP, ACCESS));
		String refresh = JsonObjects.blankToNull(configManager.getRSProfileConfiguration(GROUP, REFRESH));
		if (access == null && refresh == null
			&& configManager.getRSProfileConfiguration(GROUP, ACCOUNT_ID) == null
			&& configManager.getRSProfileConfiguration(GROUP, BOUND_ACCOUNT_HASH) == null
			&& configManager.getRSProfileConfiguration(GROUP, STATUS) == null)
		{
			return;
		}
		if (sessionFileStore.load(liveAccountHash) != null)
		{
			unsetLegacyTokenKeys();
			return;
		}
		if (access == null || refresh == null)
		{
			unsetLegacyTokenKeys();
			return;
		}
		long bound = parseBoundAccountHash(configManager.getRSProfileConfiguration(GROUP, BOUND_ACCOUNT_HASH));
		if (bound != -1L && bound != liveAccountHash)
		{
			unsetLegacyTokenKeys();
			return;
		}
		SessionData data = new SessionData();
		data.accessToken = access;
		data.refreshToken = refresh;
		data.accountId = JsonObjects.blankToNull(configManager.getRSProfileConfiguration(GROUP, ACCOUNT_ID));
		data.status = JsonObjects.blankToNull(configManager.getRSProfileConfiguration(GROUP, STATUS));
		data.boundAccountHash = liveAccountHash;
		sessionFileStore.save(liveAccountHash, data);
		unsetLegacyTokenKeys();
	}

	private void unsetLegacyTokenKeys()
	{
		configManager.unsetRSProfileConfiguration(GROUP, ACCESS);
		configManager.unsetRSProfileConfiguration(GROUP, REFRESH);
		configManager.unsetRSProfileConfiguration(GROUP, ACCOUNT_ID);
		configManager.unsetRSProfileConfiguration(GROUP, BOUND_ACCOUNT_HASH);
		configManager.unsetRSProfileConfiguration(GROUP, STATUS);
	}

	private static boolean blank(String value)
	{
		return value == null || value.isEmpty();
	}
}

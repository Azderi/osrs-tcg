package com.osrstcg.persist;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Profile-scoped companion store for the Group Ironman shared card pool.
 * <p>
 * Persists the UNION of card names owned by any group member (local player + configured
 * teammates) under a dedicated RSProfile key, kept separate from the real {@code state} blob
 * ({@link TcgStateStore}) so downstream plugins that decode {@code state} for credits / economy
 * (e.g. bronzeman-tcg) are never polluted with teammates' cards. Bronzeman-style integrations
 * that want to union the pool into their own gating should read this key directly.
 * <p>
 * Shape: RSProfile group {@code osrstcg}, key {@code groupOwnedNames} → a plain JSON array of
 * card name strings, e.g. {@code ["Abyssal whip","Twisted bow"]}.
 */
@Singleton
public class GroupCollectionStore
{
	private static final String GROUP = "osrstcg";
	public static final String OWNED_NAMES_KEY = "groupOwnedNames";
	private static final Type NAME_SET_TYPE = new TypeToken<LinkedHashSet<String>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	public GroupCollectionStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/** Loads the last persisted pooled name set, or an empty set if none has been saved yet. */
	public Set<String> load()
	{
		String raw = getProfileScoped(OWNED_NAMES_KEY);
		if (raw == null || raw.isEmpty())
		{
			return new LinkedHashSet<>();
		}
		try
		{
			Set<String> parsed = gson.fromJson(raw, NAME_SET_TYPE);
			return parsed == null ? new LinkedHashSet<>() : new LinkedHashSet<>(parsed);
		}
		catch (Exception ex)
		{
			return new LinkedHashSet<>();
		}
	}

	public void save(Collection<String> names)
	{
		writeProfileScoped(OWNED_NAMES_KEY, gson.toJson(names == null ? Set.of() : names));
	}

	public void clear()
	{
		unsetProfileScoped(OWNED_NAMES_KEY);
	}

	// Package-private indirection (overridable in tests) so unit tests don't need a real ConfigManager.
	String getProfileScoped(String key)
	{
		return configManager.getRSProfileConfiguration(GROUP, key);
	}

	void writeProfileScoped(String key, String value)
	{
		configManager.setRSProfileConfiguration(GROUP, key, value);
	}

	void unsetProfileScoped(String key)
	{
		configManager.unsetRSProfileConfiguration(GROUP, key);
	}
}

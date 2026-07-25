package com.osrstcg.persist;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the {@code groupOwnedNames} RSProfile companion key round-trips through
 * {@link GroupCollectionStore} without touching the real {@code state} blob, and that a missing
 * or corrupt value degrades to an empty set instead of throwing.
 */
public class GroupCollectionStoreTest
{
	private final Map<String, String> profile = new LinkedHashMap<>();
	private final TestableGroupCollectionStore store = new TestableGroupCollectionStore(profile);

	@Test
	public void loadReturnsEmptySetWhenNothingPersistedYet()
	{
		Assert.assertTrue(store.load().isEmpty());
	}

	@Test
	public void saveThenLoadRoundTripsNames()
	{
		Set<String> names = new LinkedHashSet<>();
		names.add("Abyssal whip");
		names.add("Twisted bow");

		store.save(names);

		Assert.assertEquals(names, store.load());
		Assert.assertTrue(
			"companion key must be plain JSON, e.g. [\"Abyssal whip\",\"Twisted bow\"]",
			profile.get(GroupCollectionStore.OWNED_NAMES_KEY).contains("Abyssal whip"));
	}

	@Test
	public void saveDoesNotTouchAnyOtherProfileKey()
	{
		profile.put("state", "RLTCG_v2:untouched");

		store.save(Set.of("Rune scimitar"));

		Assert.assertEquals("RLTCG_v2:untouched", profile.get("state"));
	}

	@Test
	public void clearRemovesThePersistedValue()
	{
		store.save(Set.of("Rune scimitar"));
		Assert.assertFalse(store.load().isEmpty());

		store.clear();

		Assert.assertTrue(store.load().isEmpty());
		Assert.assertFalse(profile.containsKey(GroupCollectionStore.OWNED_NAMES_KEY));
	}

	@Test
	public void loadIgnoresCorruptJsonInsteadOfThrowing()
	{
		profile.put(GroupCollectionStore.OWNED_NAMES_KEY, "not valid json{{{");

		Assert.assertTrue(store.load().isEmpty());
	}

	/** Overrides the RSProfile accessors with an in-memory map, avoiding a real {@code ConfigManager}. */
	private static final class TestableGroupCollectionStore extends GroupCollectionStore
	{
		private final Map<String, String> profile;

		private TestableGroupCollectionStore(Map<String, String> profile)
		{
			super(null, new com.google.gson.Gson());
			this.profile = profile;
		}

		@Override
		String getProfileScoped(String key)
		{
			return profile.get(key);
		}

		@Override
		void writeProfileScoped(String key, String value)
		{
			profile.put(key, value);
		}

		@Override
		void unsetProfileScoped(String key)
		{
			profile.remove(key);
		}
	}
}

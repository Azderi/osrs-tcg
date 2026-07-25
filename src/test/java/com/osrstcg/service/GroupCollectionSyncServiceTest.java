package com.osrstcg.service;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pure-logic tests for the Group Ironman shared card pool: merging teammate name sets, parsing
 * the {@code GET /api/v1/players/{displayName}} response body (the same {@code cardEntries} shape
 * {@link CollectionShareSnapshotBuilder#buildPayload} produces), and the never-shrink-on-failure
 * guarantee. No live network calls are made.
 */
public class GroupCollectionSyncServiceTest
{
	private static final Gson GSON = new Gson();

	/** Sample decoded {@code /players/{name}} body — same shape the website's own API serves. */
	private static final String SAMPLE_PLAYER_PAYLOAD = ""
		+ "{"
		+ "\"schemaVersion\":2,"
		+ "\"catalogVersion\":\"1.0.0\","
		+ "\"displayName\":\"Teammate One\","
		+ "\"updatedAt\":\"2024-01-01T00:00:00Z\","
		+ "\"stats\":{\"collectionScore\":10,\"completionPct\":1.0,\"uniqueOwned\":2,\"uniqueFoilOwned\":1,"
		+ "\"foilCompletionPct\":0.5,\"totalCardPool\":100,\"openedPacks\":5,\"totalCardsOwned\":3,\"customRates\":false},"
		+ "\"cardEntries\":["
		+ "{\"cardName\":\"Abyssal whip\",\"variants\":[{\"pulledBy\":\"Teammate One\",\"pulledAt\":1710000000000}]},"
		+ "{\"cardName\":\"Twisted bow\",\"variants\":["
		+ "{\"foil\":true,\"pulledBy\":\"Teammate One\",\"pulledAt\":1710000010000},"
		+ "{\"pulledBy\":\"Teammate One\",\"pulledAt\":1710000020000}"
		+ "]}"
		+ "]"
		+ "}";

	/** The exact 404 body shape osrs-tcg.xyz returns for an unpublished album. */
	private static final String NOT_FOUND_PAYLOAD = "{\"error\":\"not_found\",\"message\":\"No public album found for X\"}";

	@Test
	public void parseOwnedNamesExtractsAllCardEntryNames()
	{
		Set<String> names = GroupCollectionSyncService.parseOwnedNames(SAMPLE_PLAYER_PAYLOAD, GSON);

		Assert.assertEquals(Set.of("Abyssal whip", "Twisted bow"), names);
	}

	@Test
	public void parseOwnedNamesDedupesVariantsOfTheSameCardIntoOneName()
	{
		// "Twisted bow" has two variants (foil + normal) in the sample above but must fold to one name.
		Set<String> names = GroupCollectionSyncService.parseOwnedNames(SAMPLE_PLAYER_PAYLOAD, GSON);

		Assert.assertEquals(1, names.stream().filter("Twisted bow"::equals).count());
	}

	@Test
	public void parseOwnedNamesReturnsEmptyForNotFoundBody()
	{
		Set<String> names = GroupCollectionSyncService.parseOwnedNames(NOT_FOUND_PAYLOAD, GSON);

		Assert.assertTrue(names.isEmpty());
	}

	@Test
	public void parseOwnedNamesReturnsEmptyForNullOrBlankOrGarbageInput()
	{
		Assert.assertTrue(GroupCollectionSyncService.parseOwnedNames(null, GSON).isEmpty());
		Assert.assertTrue(GroupCollectionSyncService.parseOwnedNames("", GSON).isEmpty());
		Assert.assertTrue(GroupCollectionSyncService.parseOwnedNames("not json{{{", GSON).isEmpty());
	}

	@Test
	public void parseMembersSplitsOnCommasAndNewlinesAndTrimsAndDedupes()
	{
		List<String> members = GroupCollectionSyncService.parseMembers("Alice, Bob\nCarol\r\n  Alice  ,,Bob");

		Assert.assertEquals(List.of("Alice", "Bob", "Carol"), members);
	}

	@Test
	public void parseMembersReturnsEmptyForNullOrBlank()
	{
		Assert.assertTrue(GroupCollectionSyncService.parseMembers(null).isEmpty());
		Assert.assertTrue(GroupCollectionSyncService.parseMembers("").isEmpty());
	}

	@Test
	public void computeUnionMergesFloorLocalAndAllMembers()
	{
		Set<String> floor = Set.of("Rune scimitar");
		Set<String> local = Set.of("Abyssal whip");
		Map<String, Set<String>> members = new LinkedHashMap<>();
		members.put("teammate one", Set.of("Twisted bow"));
		members.put("teammate two", Set.of("Dragon dagger"));

		Set<String> union = GroupCollectionSyncService.computeUnion(floor, local, members);

		Assert.assertEquals(
			Set.of("Rune scimitar", "Abyssal whip", "Twisted bow", "Dragon dagger"), union);
	}

	@Test
	public void computeUnionNeverShrinksWhenAMemberFetchFails()
	{
		// Simulates: a prior successful sync cached "Twisted bow" for teammate two, then a later
		// pass fails for teammate two (fetchMember leaves the cache entry untouched) — the pool
		// recomputed from the still-populated cache must retain "Twisted bow".
		Map<String, Set<String>> memberCache = new LinkedHashMap<>();
		memberCache.put("teammate one", Set.of("Abyssal whip"));
		memberCache.put("teammate two", Set.of("Twisted bow"));

		Set<String> beforeFailure = GroupCollectionSyncService.computeUnion(Set.of(), Set.of(), memberCache);

		// A failed fetch for teammate two does NOT touch memberCache (see fetchMember: 404/error -> return).
		Set<String> afterFailedFetch = GroupCollectionSyncService.computeUnion(beforeFailure, Set.of(), memberCache);

		Assert.assertEquals(beforeFailure, afterFailedFetch);
		Assert.assertTrue(afterFailedFetch.contains("Twisted bow"));
	}

	@Test
	public void computeUnionTreatsPersistedFloorAsPermanentLastKnownBaseline()
	{
		// The floor represents names persisted from a previous session/run; even if no member
		// cache or local names currently reproduce them (e.g. right after a restart, before the
		// first fetch completes), they must not be dropped from the pool.
		Set<String> floorFromDisk = Set.of("Abyssal whip", "Twisted bow");

		Set<String> union = GroupCollectionSyncService.computeUnion(floorFromDisk, Set.of(), Map.of());

		Assert.assertEquals(floorFromDisk, union);
	}

	@Test
	public void computeUnionHandlesNullInputsGracefully()
	{
		Set<String> union = GroupCollectionSyncService.computeUnion(null, null, null);

		Assert.assertTrue(union.isEmpty());
	}

	@Test
	public void computeUnionIsCaseInsensitivelySorted()
	{
		Set<String> union = GroupCollectionSyncService.computeUnion(
			new LinkedHashSet<>(Set.of("zulrah's scales", "Abyssal whip")), Set.of(), Map.of());

		Assert.assertEquals(List.of("Abyssal whip", "zulrah's scales"), List.copyOf(union));
	}
}

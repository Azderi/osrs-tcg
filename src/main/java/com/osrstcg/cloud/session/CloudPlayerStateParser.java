package com.osrstcg.cloud.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.EconomyState;
import com.osrstcg.state.OwnedCardInstance;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless parser for the JSON payloads returned by the cloud {@code /me/state} and related
 * endpoints. Converts raw {@link JsonObject}s into typed, defensively-normalized value objects
 * ({@link ParsedCloudPlayerState}, {@link SyncMarkers}).
 */
public final class CloudPlayerStateParser
{
	private CloudPlayerStateParser()
	{
	}

	/**
	 * Parses a full {@code /me/state}-shaped root object (account/economy/stats/cards sections)
	 * into a {@link ParsedCloudPlayerState}. Returns {@link ParsedCloudPlayerState#empty()} if
	 * {@code root} is null. Cards are read from the top-level {@code cards} field, falling back to
	 * {@code collection.cards} for older payload shapes.
	 */
	public static ParsedCloudPlayerState parse(JsonObject root)
	{
		if (root == null)
		{
			return ParsedCloudPlayerState.empty();
		}

		JsonObject account = JsonObjects.objectOrEmpty(root, "account");
		JsonObject economy = JsonObjects.objectOrEmpty(root, "economy");
		JsonObject stats = JsonObjects.objectOrEmpty(root, "stats");

		List<OwnedCardInstance> cards = parseCards(root.get("cards"));
		if (cards.isEmpty() && root.has("collection") && root.get("collection").isJsonObject())
		{
			cards = parseCards(root.getAsJsonObject("collection").get("cards"));
		}

		String migratedAt = JsonObjects.text(account, "migratedAt");
		boolean migratedFlag = JsonObjects.readBoolean(account, "migrated");
		long revisionEarly = JsonObjects.readLong(economy, "revision", 0L);
		boolean migrated = (migratedAt != null && !migratedAt.isBlank())
			|| migratedFlag
			|| !cards.isEmpty();

		long credits = JsonObjects.readLong(economy, "credits", 0L);
		long openedPacks = JsonObjects.readLong(economy, "openedPacks", 0L);
		long totalCreditsGained = JsonObjects.readLong(economy, "totalCreditsGained", credits);
		long revision = revisionEarly;
		String stateHash = JsonObjects.text(economy, "stateHash");
		if (stateHash == null)
		{
			stateHash = "";
		}
		String collectionHash = JsonObjects.text(economy, "collectionHash");
		if (collectionHash == null)
		{
			collectionHash = "";
		}

		CloudSidebarCollectionStats sidebarStats = null;
		if (CloudSidebarCollectionStats.hasCollectionFields(stats) || stats.has("credits") || stats.has("openedPacks"))
		{
			sidebarStats = CloudSidebarCollectionStats.fromStatsJson(stats);
		}

		String status = JsonObjects.text(account, "status");
		boolean cardsPaged = JsonObjects.readBoolean(root, "cardsPaged");

		String groupKey = null;
		if (root.has("group") && root.get("group").isJsonObject())
		{
			groupKey = JsonObjects.text(root.getAsJsonObject("group"), "groupKey");
			if (groupKey != null && groupKey.isBlank())
			{
				groupKey = null;
			}
		}

		return new ParsedCloudPlayerState(
			migrated,
			migratedAt,
			status,
			new EconomyState(credits, openedPacks),
			totalCreditsGained,
			revision,
			stateHash,
			collectionHash,
			sidebarStats,
			cards,
			cardsPaged,
			groupKey);
	}

	/**
	 * Converts a {@code cards} JSON array into {@link OwnedCardInstance}s, skipping any element that
	 * isn't an object or is missing a usable card name. Returns an empty list if {@code cardsEl} is
	 * null or not an array.
	 */
	public static List<OwnedCardInstance> parseCards(JsonElement cardsEl)
	{
		List<OwnedCardInstance> out = new ArrayList<>();
		if (cardsEl == null || !cardsEl.isJsonArray())
		{
			return out;
		}
		JsonArray cards = cardsEl.getAsJsonArray();
		for (JsonElement el : cards)
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			JsonObject card = el.getAsJsonObject();
			String name = JsonObjects.text(card, "cardName");
			if (name == null || name.isBlank())
			{
				name = JsonObjects.text(card, "name");
			}
			if (name == null || name.isBlank())
			{
				continue;
			}
			String instanceId = JsonObjects.text(card, "instanceId");
			boolean foil = JsonObjects.readBoolean(card, "foil");
			String pulledBy = JsonObjects.text(card, "pulledBy");
			long pulledAt = Math.max(0L, JsonObjects.readLong(card, "pulledAt", 0L));
			boolean beta = JsonObjects.readBoolean(card, "beta");
			out.add(new OwnedCardInstance(instanceId, name.trim(), foil,
				pulledBy == null ? "" : pulledBy, pulledAt, beta));
		}
		return out;
	}

	/**
	 * Reads revision/stateHash/collectionHash sync markers from either a stats payload with a nested
	 * {@code economy} object or an economy object directly, falling back to top-level fields when the
	 * nested ones are absent. Returns zero/blank markers if {@code statsOrEconomy} is null.
	 */
	public static SyncMarkers readSyncMarkers(JsonObject statsOrEconomy)
	{
		if (statsOrEconomy == null)
		{
			return new SyncMarkers(0L, "", "");
		}
		JsonObject economy = statsOrEconomy.has("economy") && statsOrEconomy.get("economy").isJsonObject()
			? statsOrEconomy.getAsJsonObject("economy")
			: statsOrEconomy;
		long revision = JsonObjects.readLong(economy, "revision", JsonObjects.readLong(statsOrEconomy, "revision", 0L));
		String hash = JsonObjects.text(economy, "stateHash");
		if (hash == null)
		{
			hash = JsonObjects.text(statsOrEconomy, "stateHash");
		}
		String collectionHash = JsonObjects.text(economy, "collectionHash");
		if (collectionHash == null)
		{
			collectionHash = JsonObjects.text(statsOrEconomy, "collectionHash");
		}
		return new SyncMarkers(revision, hash == null ? "" : hash, collectionHash == null ? "" : collectionHash);
	}

	/** Revision/hash markers used to detect whether local state is behind the cloud copy. */
	public static final class SyncMarkers
	{
		public final long revision;
		public final String stateHash;
		public final String collectionHash;

		/** Normalizes revision to non-negative and hashes to trimmed, non-null strings. */
		public SyncMarkers(long revision, String stateHash, String collectionHash)
		{
			this.revision = Math.max(0L, revision);
			this.stateHash = stateHash == null ? "" : stateHash.trim();
			this.collectionHash = collectionHash == null ? "" : collectionHash.trim();
		}
	}

	/**
	 * Normalized, immutable snapshot of a cloud player-state response: migration/account status,
	 * economy totals, sync markers, and the owned card collection (which may be a placeholder when
	 * {@link #cardsPaged} is true, pending a follow-up page fetch via {@link CloudCollectionPager}).
	 */
	public static final class ParsedCloudPlayerState
	{
		public final boolean migrated;
		public final String migratedAt;
		public final String accountStatus;
		public final EconomyState economy;
		public final long totalCreditsGained;
		public final long revision;
		public final String stateHash;
		public final String collectionHash;
		public final CloudSidebarCollectionStats sidebarStats;
		public final List<OwnedCardInstance> cards;
		public final boolean cardsPaged;
		public final String groupKey;

		/** Normalizes all fields (non-negative numbers, non-null/trimmed strings, immutable card list). */
		ParsedCloudPlayerState(
			boolean migrated,
			String migratedAt,
			String accountStatus,
			EconomyState economy,
			long totalCreditsGained,
			long revision,
			String stateHash,
			String collectionHash,
			CloudSidebarCollectionStats sidebarStats,
			List<OwnedCardInstance> cards,
			boolean cardsPaged,
			String groupKey)
		{
			this.migrated = migrated;
			this.migratedAt = migratedAt;
			this.accountStatus = accountStatus;
			this.economy = economy == null ? EconomyState.empty() : economy;
			this.totalCreditsGained = Math.max(0L, totalCreditsGained);
			this.revision = Math.max(0L, revision);
			this.stateHash = stateHash == null ? "" : stateHash.trim();
			this.collectionHash = collectionHash == null ? "" : collectionHash.trim();
			this.sidebarStats = sidebarStats;
			this.cards = cards == null ? List.of() : List.copyOf(cards);
			this.cardsPaged = cardsPaged;
			this.groupKey = groupKey == null || groupKey.isBlank() ? null : groupKey.trim();
		}

		/** Returns a copy with {@code nextCards} substituted and {@code cardsPaged} cleared. */
		public ParsedCloudPlayerState withCards(List<OwnedCardInstance> nextCards)
		{
			return new ParsedCloudPlayerState(
				migrated,
				migratedAt,
				accountStatus,
				economy,
				totalCreditsGained,
				revision,
				stateHash,
				collectionHash,
				sidebarStats,
				nextCards,
				false,
				groupKey);
		}

		/** An unmigrated, zeroed-out state used when there is no cloud payload to parse. */
		static ParsedCloudPlayerState empty()
		{
			return new ParsedCloudPlayerState(false, null, null, EconomyState.empty(), 0L, 0L, "", "",
				null, List.of(), false, null);
		}
	}
}

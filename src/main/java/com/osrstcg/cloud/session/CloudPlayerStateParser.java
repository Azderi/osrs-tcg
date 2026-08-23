package com.osrstcg.cloud.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.state.CloudSidebarCollectionStats;
import com.osrstcg.state.EconomyState;
import com.osrstcg.state.OwnedCardInstance;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses structured {@code GET /api/v1/me/state} JSON (not profileBlob).
 */
public final class CloudPlayerStateParser
{
	private CloudPlayerStateParser()
	{
	}

	public static ParsedCloudPlayerState parse(JsonObject root)
	{
		if (root == null)
		{
			return ParsedCloudPlayerState.empty();
		}

		JsonObject account = objectOrEmpty(root, "account");
		JsonObject economy = objectOrEmpty(root, "economy");
		JsonObject stats = objectOrEmpty(root, "stats");

		List<OwnedCardInstance> cards = parseCards(root.get("cards"));
		if (cards.isEmpty() && root.has("collection") && root.get("collection").isJsonObject())
		{
			cards = parseCards(root.getAsJsonObject("collection").get("cards"));
		}

		String migratedAt = nullableString(account, "migratedAt");
		boolean migratedFlag = account.has("migrated") && !account.get("migrated").isJsonNull()
			&& account.get("migrated").getAsBoolean();
		long revisionEarly = readLong(economy, "revision", 0L);
		// Fresh accounts are created with revision=1 and empty cards. Do NOT treat revision alone
		// as migrated - that skipped POST /me/migrate after pair (adoptServerMigrationIfNeeded).
		// Cloud-native recovery: migratedAt, explicit migrated flag, or non-empty card list.
		boolean migrated = (migratedAt != null && !migratedAt.isBlank())
			|| migratedFlag
			|| !cards.isEmpty();

		long credits = readLong(economy, "credits", 0L);
		long openedPacks = readLong(economy, "openedPacks", 0L);
		long totalCreditsGained = readLong(economy, "totalCreditsGained", credits);
		long revision = revisionEarly;
		String stateHash = nullableString(economy, "stateHash");
		if (stateHash == null)
		{
			stateHash = "";
		}

		CloudSidebarCollectionStats sidebarStats = null;
		if (CloudSidebarCollectionStats.hasCollectionFields(stats) || stats.has("credits") || stats.has("openedPacks"))
		{
			sidebarStats = CloudSidebarCollectionStats.fromStatsJson(stats);
		}

		String status = nullableString(account, "status");
		boolean cardsPaged = root.has("cardsPaged") && !root.get("cardsPaged").isJsonNull()
			&& root.get("cardsPaged").getAsBoolean();

		return new ParsedCloudPlayerState(
			migrated,
			migratedAt,
			status,
			new EconomyState(credits, openedPacks),
			totalCreditsGained,
			revision,
			stateHash,
			sidebarStats,
			cards,
			cardsPaged);
	}

	/** Parses a {@code cards} array from {@code GET /me/state} or a {@code GET /me/cards} page. */
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
			String name = nullableString(card, "cardName");
			if (name == null || name.isBlank())
			{
				name = nullableString(card, "name");
			}
			if (name == null || name.isBlank())
			{
				continue;
			}
			String instanceId = nullableString(card, "instanceId");
			boolean foil = card.has("foil") && !card.get("foil").isJsonNull() && card.get("foil").getAsBoolean();
			boolean locked = card.has("locked") && !card.get("locked").isJsonNull() && card.get("locked").getAsBoolean();
			String pulledBy = nullableString(card, "pulledBy");
			long pulledAt = 0L;
			if (card.has("pulledAt") && !card.get("pulledAt").isJsonNull())
			{
				pulledAt = Math.max(0L, card.get("pulledAt").getAsLong());
			}
			Double condition = null;
			if (card.has("condition") && !card.get("condition").isJsonNull())
			{
				condition = card.get("condition").getAsDouble();
			}
			boolean beta = card.has("beta") && !card.get("beta").isJsonNull() && card.get("beta").getAsBoolean();
			String source = nullableString(card, "source");
			out.add(new OwnedCardInstance(instanceId, name.trim(), foil,
				pulledBy == null ? "" : pulledBy, pulledAt, locked, condition, beta, source));
		}
		return out;
	}

	/** Lightweight compare fields from {@code GET /me/stats} (revision / optional stateHash). */
	public static SyncMarkers readSyncMarkers(JsonObject statsOrEconomy)
	{
		if (statsOrEconomy == null)
		{
			return new SyncMarkers(0L, "");
		}
		JsonObject economy = statsOrEconomy.has("economy") && statsOrEconomy.get("economy").isJsonObject()
			? statsOrEconomy.getAsJsonObject("economy")
			: statsOrEconomy;
		long revision = readLong(economy, "revision", readLong(statsOrEconomy, "revision", 0L));
		String hash = nullableString(economy, "stateHash");
		if (hash == null)
		{
			hash = nullableString(statsOrEconomy, "stateHash");
		}
		return new SyncMarkers(revision, hash == null ? "" : hash);
	}

	private static JsonObject objectOrEmpty(JsonObject root, String key)
	{
		if (root.has(key) && root.get(key).isJsonObject())
		{
			return root.getAsJsonObject(key);
		}
		return new JsonObject();
	}

	private static String nullableString(JsonObject obj, String key)
	{
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull())
		{
			return null;
		}
		String value = obj.get(key).getAsString();
		return value == null ? null : value;
	}

	private static long readLong(JsonObject obj, String key, long fallback)
	{
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull())
		{
			return fallback;
		}
		try
		{
			return obj.get(key).getAsLong();
		}
		catch (RuntimeException ex)
		{
			return fallback;
		}
	}

	public static final class SyncMarkers
	{
		public final long revision;
		public final String stateHash;

		public SyncMarkers(long revision, String stateHash)
		{
			this.revision = Math.max(0L, revision);
			this.stateHash = stateHash == null ? "" : stateHash.trim();
		}
	}

	public static final class ParsedCloudPlayerState
	{
		public final boolean migrated;
		public final String migratedAt;
		public final String accountStatus;
		public final EconomyState economy;
		public final long totalCreditsGained;
		public final long revision;
		public final String stateHash;
		public final CloudSidebarCollectionStats sidebarStats;
		public final List<OwnedCardInstance> cards;
		/**
		 * When true, {@link #cards} from {@code GET /me/state} is intentionally empty and instances
		 * must be loaded via chunked {@code GET /me/cards}.
		 */
		public final boolean cardsPaged;

		ParsedCloudPlayerState(
			boolean migrated,
			String migratedAt,
			String accountStatus,
			EconomyState economy,
			long totalCreditsGained,
			long revision,
			String stateHash,
			CloudSidebarCollectionStats sidebarStats,
			List<OwnedCardInstance> cards,
			boolean cardsPaged)
		{
			this.migrated = migrated;
			this.migratedAt = migratedAt;
			this.accountStatus = accountStatus;
			this.economy = economy == null ? EconomyState.empty() : economy;
			this.totalCreditsGained = Math.max(0L, totalCreditsGained);
			this.revision = Math.max(0L, revision);
			this.stateHash = stateHash == null ? "" : stateHash.trim();
			this.sidebarStats = sidebarStats;
			this.cards = cards == null ? List.of() : List.copyOf(cards);
			this.cardsPaged = cardsPaged;
		}

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
				sidebarStats,
				nextCards,
				false);
		}

		static ParsedCloudPlayerState empty()
		{
			return new ParsedCloudPlayerState(false, null, null, EconomyState.empty(), 0L, 0L, "",
				null, List.of(), false);
		}
	}
}

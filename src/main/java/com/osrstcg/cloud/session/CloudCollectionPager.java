package com.osrstcg.cloud.session;

import com.osrstcg.state.OwnedCardInstance;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.cloud.api.CloudApiClient;

/** Paginates {@code GET /me/cards} when {@code GET /me/state} reports {@code cardsPaged}. */
@Slf4j
final class CloudCollectionPager
{
	/** Matches website {@code ALBUM_PAGE_LIMIT} / server default page size. */
	static final int ME_CARDS_PAGE_LIMIT = 500;
	static final int ME_CARDS_MAX_PAGES = 200;

	private final CloudApiClient api;

	CloudCollectionPager(CloudApiClient api)
	{
		this.api = api;
	}

	/**
	 * Parse {@code GET /me/state} and, when {@code cardsPaged}, load instances via {@code GET /me/cards}.
	 * Retries once if paging sees a revision change mid-fetch.
	 */
	CloudPlayerStateParser.ParsedCloudPlayerState loadCloudPlayerStateWithCards(JsonObject stateJson)
		throws Exception
	{
		CloudPlayerStateParser.ParsedCloudPlayerState parsed = CloudPlayerStateParser.parse(stateJson);
		try
		{
			return resolveCardsForState(parsed);
		}
		catch (IOException ex)
		{
			String msg = ex.getMessage();
			if (msg == null || !msg.startsWith("me/cards revision drift"))
			{
				throw ex;
			}
			log.info("me/cards revision changed during paging; retrying full state pull");
			return resolveCardsForState(CloudPlayerStateParser.parse(api.getState()));
		}
	}

	/**
	 * {@code GET /me/state} returns an empty {@code cards} array when {@code cardsPaged} is true;
	 * load instances via chunked {@code GET /me/cards} before applying local state.
	 */
	CloudPlayerStateParser.ParsedCloudPlayerState resolveCardsForState(
		CloudPlayerStateParser.ParsedCloudPlayerState parsed) throws Exception
	{
		if (parsed == null || !parsed.cardsPaged)
		{
			return parsed;
		}
		List<OwnedCardInstance> cards = fetchAllOwnedCards(parsed.revision);
		return parsed.withCards(cards);
	}

	List<OwnedCardInstance> fetchAllOwnedCards(long expectedRevision) throws Exception
	{
		List<OwnedCardInstance> all = new ArrayList<>();
		String cursor = null;
		boolean hasMore = true;
		int pages = 0;
		while (hasMore)
		{
			if (++pages > ME_CARDS_MAX_PAGES)
			{
				throw new IOException("me/cards pagination exceeded " + ME_CARDS_MAX_PAGES + " pages");
			}
			JsonObject page = api.getCardsPage(ME_CARDS_PAGE_LIMIT, cursor);
			long pageRevision = page.has("revision") && !page.get("revision").isJsonNull()
				? Math.max(0L, page.get("revision").getAsLong())
				: expectedRevision;
			if (pageRevision != expectedRevision)
			{
				throw new IOException("me/cards revision drift (" + pageRevision + " vs " + expectedRevision + ")");
			}
			all.addAll(CloudPlayerStateParser.parseCards(page.get("cards")));
			hasMore = page.has("hasMore") && !page.get("hasMore").isJsonNull()
				&& page.get("hasMore").getAsBoolean();
			if (hasMore)
			{
				cursor = page.has("nextCursor") && !page.get("nextCursor").isJsonNull()
					? page.get("nextCursor").getAsString()
					: null;
				if (cursor == null || cursor.isBlank())
				{
					throw new IOException("me/cards hasMore without nextCursor");
				}
			}
		}
		return all;
	}
}

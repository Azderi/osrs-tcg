package com.osrstcg.cloud.session;

import com.osrstcg.state.OwnedCardInstance;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import com.osrstcg.cloud.api.CloudApiClient;

/**
 * Fetches the full owned-card collection from {@code /me/cards} in pages when the player state
 * response indicates cards are paginated, and retries the whole state pull once if the server
 * revision drifts mid-paging. Blocking: every method here issues synchronous HTTP calls via
 * {@link CloudApiClient} and must be called off the client/EDT thread.
 */
@Slf4j
final class CloudCollectionPager
{
	static final int ME_CARDS_PAGE_LIMIT = 500;
	static final int ME_CARDS_MAX_PAGES = 200;

	private final CloudApiClient api;

	/** Wires the API client; no side effects. */
	CloudCollectionPager(CloudApiClient api)
	{
		this.api = api;
	}

	/**
	 * Parses {@code stateJson} and, if cards are paginated, fetches all pages from {@code /me/cards}.
	 * On a revision drift mid-paging, re-fetches the full state once and pages again against the new revision.
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

	/** Returns {@code parsed} unchanged if cards aren't paginated, otherwise fills in cards fetched by page. */
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

	/**
	 * Pages through {@code /me/cards} until {@code hasMore} is false, verifying each page reports
	 * {@code expectedRevision}. Throws if a page's revision doesn't match (caller should retry from
	 * fresh state), if paging exceeds {@link #ME_CARDS_MAX_PAGES}, or if a page claims more results
	 * without a cursor.
	 */
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

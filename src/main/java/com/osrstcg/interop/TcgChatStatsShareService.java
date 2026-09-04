package com.osrstcg.interop;

import com.osrstcg.state.TcgPublicStats;
import com.osrstcg.util.NumberFormatting;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
/**
 * Caches recent {@link TcgPublicStats} per sanitized RSN from {@code GET /players/:name/stats}
 * so chat substitution can paint immediately on cache hit.
 */
@Singleton
public class TcgChatStatsShareService
{
	private static final long CACHE_TTL_MS = 15L * 60L * 1000L;
/** A cached stats snapshot plus the time it was stored, for TTL expiry. */
	private static final class CacheEntry
	{
		private final TcgPublicStats stats;
		private final long storedAtMs;
/** Stores the snapshot and its capture time. */
		private CacheEntry(TcgPublicStats stats, long storedAtMs)
		{
			this.stats = stats;
			this.storedAtMs = storedAtMs;
		}
	}

	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
/** No collaborators to wire; the cache is self-contained. */
	@Inject
	public TcgChatStatsShareService()
	{
	}
/** Caches {@code stats} under the normalized RSN key; no-op if either argument is null/empty. */
	public void putSanitizedPlayerName(String sanitizedRsn, TcgPublicStats stats)
	{
		if (sanitizedRsn == null || sanitizedRsn.isEmpty() || stats == null)
		{
			return;
		}
		String key = normalizeKey(sanitizedRsn);
		cache.put(key, new CacheEntry(stats, System.currentTimeMillis()));
	}
/** Returns the cached stats for {@code sanitizedRsn}, or null if absent, empty input, or expired (evicting it). */
	public TcgPublicStats getBySanitizedPlayerName(String sanitizedRsn)
	{
		if (sanitizedRsn == null || sanitizedRsn.isEmpty())
		{
			return null;
		}
		String key = normalizeKey(sanitizedRsn);
		CacheEntry e = cache.get(key);
		if (e == null)
		{
			return null;
		}
		if (System.currentTimeMillis() - e.storedAtMs > CACHE_TTL_MS)
		{
			cache.remove(key, e);
			return null;
		}
		return e.stats;
	}
/** Renders {@code s} as a chat-colored summary line via {@link ChatMessageBuilder}. */
	public String buildColoredLine(TcgPublicStats s)
	{
		return buildFormattedLine(s, true);
	}
/** Renders {@code s} as a plain-text summary line (no chat color codes). */
	public String buildPlainLine(TcgPublicStats s)
	{
		return buildFormattedLine(s, false);
	}
/** Shared formatter behind {@link #buildColoredLine} and {@link #buildPlainLine}. */
	private static String buildFormattedLine(TcgPublicStats s, boolean colored)
	{
		String pct = String.format(Locale.US, "%.2f%%", s.getCompletionPct());
		String foilPct = String.format(Locale.US, "%.2f%%", s.getFoilCompletionPct());
		if (colored)
		{
			ChatMessageBuilder builder = TcgPluginGameMessages.prefixBuilder()
				.append(ChatColorType.NORMAL)
				.append("Collection score: ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getCollectionScore()))
				.append(ChatColorType.NORMAL)
				.append(" (")
				.append(ChatColorType.HIGHLIGHT)
				.append(pct)
				.append(ChatColorType.NORMAL)
				.append("), Unique cards: ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getUniqueOwned()))
				.append(ChatColorType.NORMAL)
				.append(" / ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getTotalCardPool()))
				.append(ChatColorType.NORMAL)
				.append(" (")
				.append(ChatColorType.HIGHLIGHT)
				.append(pct)
				.append(ChatColorType.NORMAL)
				.append("), Unique foil cards: ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getUniqueFoilOwned()))
				.append(ChatColorType.NORMAL)
				.append(" / ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getTotalCardPool()))
				.append(ChatColorType.NORMAL)
				.append(" (")
				.append(ChatColorType.HIGHLIGHT)
				.append(foilPct)
				.append(ChatColorType.NORMAL)
				.append("), Opened packs: ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getOpenedPacks()))
				.append(ChatColorType.NORMAL)
				.append(", Total cards: ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getTotalCardsOwned()))
				.append(ChatColorType.NORMAL)
				.append(", Total foil cards: ")
				.append(ChatColorType.HIGHLIGHT)
				.append(NumberFormatting.format(s.getFoilOwned()));
			if (s.isCustomRates())
			{
				builder.append(ChatColorType.NORMAL)
					.append(" (custom rates)");
			}
			return builder.build();
		}

		StringBuilder plain = new StringBuilder()
			.append(TcgPluginGameMessages.plainPrefix())
			.append("Collection score: ")
			.append(NumberFormatting.format(s.getCollectionScore()))
			.append(" (")
			.append(pct)
			.append("), Unique cards: ")
			.append(NumberFormatting.format(s.getUniqueOwned()))
			.append(" / ")
			.append(NumberFormatting.format(s.getTotalCardPool()))
			.append(" (")
			.append(pct)
			.append("), Unique foil cards: ")
			.append(NumberFormatting.format(s.getUniqueFoilOwned()))
			.append(" / ")
			.append(NumberFormatting.format(s.getTotalCardPool()))
			.append(" (")
			.append(foilPct)
			.append("), Opened packs: ")
			.append(NumberFormatting.format(s.getOpenedPacks()))
			.append(", Total cards: ")
			.append(NumberFormatting.format(s.getTotalCardsOwned()))
			.append(", Total foil cards: ")
			.append(NumberFormatting.format(s.getFoilOwned()));
		if (s.isCustomRates())
		{
			plain.append(" (custom rates)");
		}
		return plain.toString();
	}
/** Trims and lowercases an RSN into its cache key. */
	private static String normalizeKey(String sanitizedRsn)
	{
		return sanitizedRsn.trim().toLowerCase(Locale.ROOT);
	}
}

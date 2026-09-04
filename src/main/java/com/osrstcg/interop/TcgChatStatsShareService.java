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
		String pool = NumberFormatting.format(s.getTotalCardPool());
		// Alternating label (even) / value (odd) — colored mode uses NORMAL/HIGHLIGHT on that cadence.
		String[] segs = {
			"Collection score: ", NumberFormatting.format(s.getCollectionScore()),
			" (", pct,
			"), Unique cards: ", NumberFormatting.format(s.getUniqueOwned()),
			" / ", pool,
			" (", pct,
			"), Unique foil cards: ", NumberFormatting.format(s.getUniqueFoilOwned()),
			" / ", pool,
			" (", foilPct,
			"), Opened packs: ", NumberFormatting.format(s.getOpenedPacks()),
			", Total cards: ", NumberFormatting.format(s.getTotalCardsOwned()),
			", Total foil cards: ", NumberFormatting.format(s.getFoilOwned())
		};
		if (colored)
		{
			ChatMessageBuilder builder = TcgPluginGameMessages.prefixBuilder();
			for (int i = 0; i < segs.length; i++)
			{
				builder.append(i % 2 == 0 ? ChatColorType.NORMAL : ChatColorType.HIGHLIGHT)
					.append(segs[i]);
			}
			if (s.isCustomRates())
			{
				builder.append(ChatColorType.NORMAL).append(" (custom rates)");
			}
			return builder.build();
		}
		StringBuilder plain = new StringBuilder(TcgPluginGameMessages.plainPrefix());
		for (String seg : segs)
		{
			plain.append(seg);
		}
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

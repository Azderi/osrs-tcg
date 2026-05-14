package com.runelitetcg.service;

import com.runelitetcg.model.TcgPublicStats;
import com.runelitetcg.party.TcgChatStatsPartyMessage;
import com.runelitetcg.util.NumberFormatting;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.util.Text;

/**
 * Caches recent {@link TcgPublicStats} per sanitized RSN (from {@code !tcg} submit + party payloads) so chat
 * substitution works like stock {@code !layout} without a dedicated RuneLite HTTP chat API.
 */
@Singleton
public class TcgChatStatsShareService
{
	private static final long CACHE_TTL_MS = 15L * 60L * 1000L;

	private static final class CacheEntry
	{
		private final TcgPublicStats stats;
		private final long storedAtMs;

		private CacheEntry(TcgPublicStats stats, long storedAtMs)
		{
			this.stats = stats;
			this.storedAtMs = storedAtMs;
		}
	}

	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	@Inject
	public TcgChatStatsShareService()
	{
	}

	public void putSanitizedPlayerName(String sanitizedRsn, TcgPublicStats stats)
	{
		if (sanitizedRsn == null || sanitizedRsn.isEmpty() || stats == null)
		{
			return;
		}
		String key = normalizeKey(sanitizedRsn);
		cache.put(key, new CacheEntry(stats, System.currentTimeMillis()));
	}

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

	public void ingestPartyMessage(TcgChatStatsPartyMessage message, PartyService partyService)
	{
		if (message == null || partyService == null)
		{
			return;
		}
		PartyMember author = partyService.getMemberById(message.getMemberId());
		if (author == null)
		{
			return;
		}
		String dn = author.getDisplayName();
		if (dn == null || dn.trim().isEmpty())
		{
			return;
		}
		TcgPublicStats stats = new TcgPublicStats(
			message.getCollectionScore(),
			message.getCompletionPct(),
			message.getUniqueOwned(),
			message.getTotalCardPool(),
			message.getOpenedPacks(),
			message.getTotalCardsOwned());
		putSanitizedPlayerName(Text.sanitize(dn), stats);
	}

	public String buildColoredLine(TcgPublicStats s)
	{
		String pct = String.format(Locale.US, "%.1f%%", s.getCompletionPct());
		return new ChatMessageBuilder()
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
			.append(", Opened packs: ")
			.append(ChatColorType.HIGHLIGHT)
			.append(NumberFormatting.format(s.getOpenedPacks()))
			.append(ChatColorType.NORMAL)
			.append(", Total cards: ")
			.append(ChatColorType.HIGHLIGHT)
			.append(NumberFormatting.format(s.getTotalCardsOwned()))
			.build();
	}

	private static String normalizeKey(String sanitizedRsn)
	{
		return sanitizedRsn.trim().toLowerCase(Locale.ROOT);
	}
}

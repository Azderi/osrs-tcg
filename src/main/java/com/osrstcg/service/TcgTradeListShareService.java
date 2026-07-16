package com.osrstcg.service;

import com.osrstcg.model.CardCollectionKey;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.RewardTuningState;
import com.osrstcg.model.TcgState;
import com.osrstcg.party.TcgTradeListPartyMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.util.Text;

/**
 * Shares and caches opt-in duplicate lists from RuneLite party members so players can find useful card swaps without
 * exposing full collections or changing the existing one-way send flow.
 */
@Singleton
public class TcgTradeListShareService
{
	private static final int SCHEMA_VERSION = 1;
	private static final long CACHE_TTL_MS = 15L * 60L * 1000L;

	private final PartyService partyService;
	private final TcgStateService stateService;
	private final ConcurrentHashMap<Long, CacheEntry> cacheByMemberId = new ConcurrentHashMap<>();

	@Inject
	public TcgTradeListShareService(PartyService partyService, TcgStateService stateService)
	{
		this.partyService = partyService;
		this.stateService = stateService;
	}

	@Value
	public static class TradeMatch
	{
		String cardName;
		boolean foil;
		int available;
		boolean transferCompatible;
	}

	public String shareDuplicateList(long recipientMemberId)
	{
		String partyError = validateRecipient(recipientMemberId);
		if (partyError != null)
		{
			return partyError;
		}

		List<TcgTradeListPartyMessage.Entry> duplicates = buildDuplicateEntries();
		TcgState state = stateService.getState();
		RewardTuningState tuning = state.getRewardTuning();

		TcgTradeListPartyMessage message = new TcgTradeListPartyMessage();
		message.setSchemaVersion(SCHEMA_VERSION);
		message.setRecipientMemberId(recipientMemberId);
		message.setSentAtEpochMs(System.currentTimeMillis());
		message.setSenderDebugLogging(state.isDebugLogging());
		message.setFoilChancePercent(tuning.getFoilChancePercent());
		message.setKillCreditMultiplier(tuning.getKillCreditMultiplier());
		message.setLevelUpCreditMultiplier(tuning.getLevelUpCreditMultiplier());
		message.setXpCreditMultiplier(tuning.getXpCreditMultiplier());
		message.setDuplicates(duplicates);
		try
		{
			partyService.send(message);
		}
		catch (Exception ex)
		{
			return "Could not share dupes (party connection).";
		}

		return String.format(Locale.US, "Shared %d duplicate card variant%s.",
			duplicates.size(), duplicates.size() == 1 ? "" : "s");
	}

	public boolean hasRecentPartyLists()
	{
		pruneExpired();
		return !cacheByMemberId.isEmpty();
	}

	public String buildPartyTradeMatchSummary()
	{
		pruneExpired();
		if (cacheByMemberId.isEmpty())
		{
			return "No party duplicate lists cached yet.\n\nAsk a party member to click Share dupes.";
		}

		Map<CardCollectionKey, Integer> mine = stateService.getState().getCollectionState().getOwnedCards();
		List<CacheEntry> entries = new ArrayList<>(cacheByMemberId.values());
		entries.sort(Comparator.comparing(e -> e.displayName, String.CASE_INSENSITIVE_ORDER));

		StringBuilder out = new StringBuilder();
		for (CacheEntry entry : entries)
		{
			List<String> matches = missingFromMine(entry, mine);
			out.append(entry.displayName).append(" - ")
				.append(entry.duplicates.size()).append(" duplicate variant")
				.append(entry.duplicates.size() == 1 ? "" : "s");
			if (!entry.transferCompatible)
			{
				out.append(" (rates/debug mismatch)");
			}
			out.append('\n');

			if (matches.isEmpty())
			{
				out.append("  No shared duplicates are missing from your collection.\n\n");
				continue;
			}

			for (String line : matches)
			{
				out.append("  ").append(line).append('\n');
			}
			out.append('\n');
		}
		return out.toString().trim();
	}

	public List<TradeMatch> matchesForMember(long memberId)
	{
		pruneExpired();
		CacheEntry entry = cacheByMemberId.get(memberId);
		if (entry == null)
		{
			return List.of();
		}
		Map<CardCollectionKey, Integer> mine = stateService.getState().getCollectionState().getOwnedCards();
		List<TradeMatch> matches = new ArrayList<>();
		for (TcgTradeListPartyMessage.Entry duplicate : entry.duplicates)
		{
			String name = duplicate.getCardName();
			if (duplicate.getNormalAvailable() > 0 && !ownsVariant(mine, name, false))
			{
				matches.add(new TradeMatch(name, false, duplicate.getNormalAvailable(), entry.transferCompatible));
			}
			if (duplicate.getFoilAvailable() > 0 && !ownsVariant(mine, name, true))
			{
				matches.add(new TradeMatch(name, true, duplicate.getFoilAvailable(), entry.transferCompatible));
			}
		}
		matches.sort(Comparator.comparing(TradeMatch::getCardName, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(TradeMatch::isFoil));
		return matches;
	}

	public String displayNameForMember(long memberId)
	{
		PartyMember member = partyService.getMemberById(memberId);
		if (member != null)
		{
			return cleanDisplayName(member.getDisplayName());
		}
		CacheEntry entry = cacheByMemberId.get(memberId);
		return entry == null ? "Party member" : entry.displayName;
	}

	@Subscribe
	public void onTcgTradeListPartyMessage(TcgTradeListPartyMessage message)
	{
		if (message == null || message.getSchemaVersion() != SCHEMA_VERSION)
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || message.getRecipientMemberId() != local.getMemberId())
		{
			return;
		}

		PartyMember author = partyService.getMemberById(message.getMemberId());
		String displayName = author == null ? "Party member" : cleanDisplayName(author.getDisplayName());
		List<TcgTradeListPartyMessage.Entry> duplicates = normalizeEntries(message.getDuplicates());
		if (duplicates.isEmpty())
		{
			cacheByMemberId.remove(message.getMemberId());
			return;
		}

		RewardTuningState mine = stateService.getState().getRewardTuning();
		RewardTuningState theirs = RewardTuningState.mergeSerialized(
			message.getFoilChancePercent(),
			message.getKillCreditMultiplier(),
			message.getLevelUpCreditMultiplier(),
			message.getXpCreditMultiplier());
		boolean compatible = mine.matchesPartnerTuning(theirs)
			&& message.isSenderDebugLogging() == stateService.isDebugLogging();

		cacheByMemberId.put(message.getMemberId(), new CacheEntry(
			displayName, duplicates, System.currentTimeMillis(), compatible));
	}

	private String validateRecipient(long recipientMemberId)
	{
		if (!partyService.isInParty())
		{
			return "Join a RuneLite party first.";
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null)
		{
			return "Party session not ready.";
		}
		if (recipientMemberId == local.getMemberId())
		{
			return "Choose a different party member.";
		}
		if (partyService.getMemberById(recipientMemberId) == null)
		{
			return "That player is not in your party.";
		}
		return null;
	}

	private List<TcgTradeListPartyMessage.Entry> buildDuplicateEntries()
	{
		CollectionState collection = stateService.getState().getCollectionState();
		Map<CardCollectionKey, Counts> countsByVariant = new HashMap<>();
		for (OwnedCardInstance instance : collection.getOwnedInstances())
		{
			if (instance == null || instance.getCardName() == null || instance.getCardName().trim().isEmpty())
			{
				continue;
			}
			CardCollectionKey key = new CardCollectionKey(instance.getCardName().trim(), instance.isFoil());
			Counts counts = countsByVariant.computeIfAbsent(key, k -> new Counts());
			counts.total++;
			if (!instance.isLocked())
			{
				counts.unlocked++;
			}
		}

		Map<String, TcgTradeListPartyMessage.Entry> byName = new LinkedHashMap<>();
		List<Map.Entry<CardCollectionKey, Counts>> variants = new ArrayList<>(countsByVariant.entrySet());
		variants.sort(Comparator.comparing(e -> e.getKey().getCardName(), String.CASE_INSENSITIVE_ORDER));
		for (Map.Entry<CardCollectionKey, Counts> e : variants)
		{
			int available = e.getValue().availableDuplicates();
			if (available <= 0)
			{
				continue;
			}
			String name = e.getKey().getCardName();
			TcgTradeListPartyMessage.Entry entry = byName.computeIfAbsent(name, n ->
			{
				TcgTradeListPartyMessage.Entry next = new TcgTradeListPartyMessage.Entry();
				next.setCardName(n);
				return next;
			});
			if (e.getKey().isFoil())
			{
				entry.setFoilAvailable(available);
			}
			else
			{
				entry.setNormalAvailable(available);
			}
		}
		return new ArrayList<>(byName.values());
	}

	private List<String> missingFromMine(CacheEntry entry, Map<CardCollectionKey, Integer> mine)
	{
		List<String> matches = new ArrayList<>();
		for (TcgTradeListPartyMessage.Entry duplicate : entry.duplicates)
		{
			String name = duplicate.getCardName();
			if (duplicate.getNormalAvailable() > 0 && !ownsVariant(mine, name, false))
			{
				matches.add(formatMatch(name, false, duplicate.getNormalAvailable()));
			}
			if (duplicate.getFoilAvailable() > 0 && !ownsVariant(mine, name, true))
			{
				matches.add(formatMatch(name, true, duplicate.getFoilAvailable()));
			}
		}
		matches.sort(String.CASE_INSENSITIVE_ORDER);
		return matches;
	}

	private static boolean ownsVariant(Map<CardCollectionKey, Integer> owned, String cardName, boolean foil)
	{
		Integer count = owned.get(new CardCollectionKey(cardName, foil));
		return count != null && count > 0;
	}

	private static String formatMatch(String cardName, boolean foil, int available)
	{
		return String.format(Locale.US, "%s%s (%d available)",
			cardName, foil ? " (foil)" : "", available);
	}

	private List<TcgTradeListPartyMessage.Entry> normalizeEntries(List<TcgTradeListPartyMessage.Entry> raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return List.of();
		}
		Map<String, TcgTradeListPartyMessage.Entry> byName = new LinkedHashMap<>();
		for (TcgTradeListPartyMessage.Entry row : raw)
		{
			if (row == null || row.getCardName() == null || row.getCardName().trim().isEmpty())
			{
				continue;
			}
			String name = row.getCardName().trim();
			int normal = Math.max(0, row.getNormalAvailable());
			int foil = Math.max(0, row.getFoilAvailable());
			if (normal == 0 && foil == 0)
			{
				continue;
			}
			TcgTradeListPartyMessage.Entry entry = byName.computeIfAbsent(name, n ->
			{
				TcgTradeListPartyMessage.Entry next = new TcgTradeListPartyMessage.Entry();
				next.setCardName(n);
				return next;
			});
			entry.setNormalAvailable(entry.getNormalAvailable() + normal);
			entry.setFoilAvailable(entry.getFoilAvailable() + foil);
		}
		return new ArrayList<>(byName.values());
	}

	private void pruneExpired()
	{
		long now = System.currentTimeMillis();
		cacheByMemberId.entrySet().removeIf(e -> now - e.getValue().storedAtMs > CACHE_TTL_MS);
	}

	private static String cleanDisplayName(String displayName)
	{
		String cleaned = displayName == null ? "" : Text.removeTags(displayName).trim();
		return cleaned.isEmpty() ? "Party member" : cleaned;
	}

	private static final class Counts
	{
		private int total;
		private int unlocked;

		private int availableDuplicates()
		{
			return Math.min(unlocked, Math.max(0, total - 1));
		}
	}

	private static final class CacheEntry
	{
		private final String displayName;
		private final List<TcgTradeListPartyMessage.Entry> duplicates;
		private final long storedAtMs;
		private final boolean transferCompatible;

		private CacheEntry(String displayName, List<TcgTradeListPartyMessage.Entry> duplicates,
			long storedAtMs, boolean transferCompatible)
		{
			this.displayName = displayName;
			this.duplicates = duplicates;
			this.storedAtMs = storedAtMs;
			this.transferCompatible = transferCompatible;
		}
	}
}

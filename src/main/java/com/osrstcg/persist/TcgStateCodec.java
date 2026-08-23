package com.osrstcg.persist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrstcg.state.CardEntry;
import com.osrstcg.state.CardEntrySerializer;
import com.osrstcg.state.CollectionState;
import com.osrstcg.state.EconomyState;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.state.SkillCreditBaseline;
import com.osrstcg.state.TcgState;
import com.osrstcg.util.PackRevealZoomUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TcgStateCodec
{
	private final Gson gson;

	@Inject
	public TcgStateCodec(Gson gson)
	{
		this.gson = gson;
	}

	public Optional<TcgState> tryFromJson(String rawState)
	{
		try
		{
			String json = Objects.requireNonNullElse(rawState, "");
			if (json.isEmpty())
			{
				return Optional.empty();
			}

			SerializedState stored = gson.fromJson(json, SerializedState.class);
			if (stored == null)
			{
				return Optional.empty();
			}

			return Optional.of(parseSerializedState(stored));
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Failed to deserialize OSRS TCG state", ex);
			return Optional.empty();
		}
	}

	public TcgState fromJson(String rawState)
	{
		return tryFromJson(rawState).orElseGet(TcgState::empty);
	}

	private TcgState parseSerializedState(SerializedState stored)
	{
		List<OwnedCardInstance> rows = parseCollectionRows(stored);
		CollectionState coll = CollectionState.copyOf(rows);

		boolean debug = Boolean.TRUE.equals(stored.debugLogging);
		double packZoom = stored.packRevealOverlayScale == null
			? 1.0d
			: PackRevealZoomUtil.clamp(stored.packRevealOverlayScale);
		SkillCreditBaseline skillBaseline = parseSkillCreditBaseline(stored.skillCreditBaseline);

		long totalGained = stored.totalCreditsGained == null ? 0L : Math.max(0L, stored.totalCreditsGained);
		// 0 = missing/legacy; caller may stamp "now" on first schema-5 persist.
		long createdAt = stored.profileCreatedAtUnix == null ? 0L : Math.max(0L, stored.profileCreatedAtUnix);
		long savedAt = stored.profileSavedAtUnix == null ? 0L : Math.max(0L, stored.profileSavedAtUnix);
		long cloudRevision = stored.cloudRevision == null ? 0L : Math.max(0L, stored.cloudRevision);
		String cloudStateHash = stored.cloudStateHash == null ? "" : stored.cloudStateHash.trim();
		int[] sidebarRanks = TcgState.copyRanks(stored.sidebarRanks);

		int loadedSchema = stored.schemaVersion;
		if (loadedSchema > TcgState.CURRENT_SCHEMA_VERSION)
		{
			log.warn("TCG state schema {} is newer than supported {}; loading with best effort",
				loadedSchema, TcgState.CURRENT_SCHEMA_VERSION);
		}

		// Always materialize the current schema (upgrades older profiles).
		return new TcgState(
			TcgState.CURRENT_SCHEMA_VERSION,
			new EconomyState(stored.credits, stored.openedPacks),
			coll,
			debug,
			packZoom,
			skillBaseline,
			totalGained,
			createdAt,
			savedAt,
			cloudRevision,
			cloudStateHash,
			sidebarRanks
		);
	}

	private static List<OwnedCardInstance> parseCollectionRows(SerializedState stored)
	{
		if (stored.cardEntries != null && !stored.cardEntries.isEmpty())
		{
			return CardEntrySerializer.expandToInstances(stored.cardEntries);
		}
		return parseLegacyCardInstances(stored.cardInstances);
	}

	private static List<OwnedCardInstance> parseLegacyCardInstances(List<SerializedInstance> cardInstances)
	{
		List<OwnedCardInstance> rows = new ArrayList<>();
		if (cardInstances == null)
		{
			return rows;
		}
		for (SerializedInstance row : cardInstances)
		{
			if (row == null || row.cardName == null || row.cardName.trim().isEmpty())
			{
				continue;
			}
			String id = row.id == null || row.id.trim().isEmpty() ? null : row.id.trim();
			String by = row.pulledBy == null ? "" : row.pulledBy;
			long at = row.pulledAt <= 0L ? 0L : row.pulledAt;
			rows.add(new OwnedCardInstance(id, row.cardName.trim(), row.foil, by, at, row.locked,
				row.condition, Boolean.TRUE.equals(row.beta), row.source));
		}
		return rows;
	}

	public String toJson(TcgState state)
	{
		TcgState s = Objects.requireNonNullElse(state, TcgState.empty());
		SerializedState serialized = new SerializedState();
		serialized.schemaVersion = TcgState.CURRENT_SCHEMA_VERSION;
		serialized.credits = s.getEconomyState().getCredits();
		serialized.openedPacks = s.getEconomyState().getOpenedPacks();
		serialized.cardEntries = CardEntrySerializer.buildProfileEntries(
			s.getCollectionState().getOwnedInstances());

		serialized.debugLogging = s.isDebugLogging();
		serialized.packRevealOverlayScale = s.getPackRevealOverlayScale();
		serialized.skillCreditBaseline = serializeSkillCreditBaseline(s.getSkillCreditBaseline());
		serialized.totalCreditsGained = s.getTotalCreditsGained();
		serialized.profileCreatedAtUnix = s.getProfileCreatedAtUnix();
		serialized.profileSavedAtUnix = s.getProfileSavedAtUnix();
		if (s.getCloudRevision() > 0L)
		{
			serialized.cloudRevision = s.getCloudRevision();
		}
		if (s.getCloudStateHash() != null && !s.getCloudStateHash().isEmpty())
		{
			serialized.cloudStateHash = s.getCloudStateHash();
		}
		int[] ranks = s.getSidebarRanks();
		if (ranks != null)
		{
			serialized.sidebarRanks = ranks;
		}

		return gson.toJson(serialized);
	}

	private static SkillCreditBaseline parseSkillCreditBaseline(SerializedSkillCreditBaseline stored)
	{
		if (stored == null)
		{
			return SkillCreditBaseline.missing();
		}
		// Empty placeholder written during schema upgrade.
		if (stored.skillXp == null || stored.skillXp.isEmpty())
		{
			return SkillCreditBaseline.absent();
		}

		Map<String, Integer> xp = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : stored.skillXp.entrySet())
		{
			if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null)
			{
				continue;
			}
			xp.put(e.getKey(), Math.max(0, e.getValue()));
		}
		if (xp.isEmpty())
		{
			return SkillCreditBaseline.absent();
		}
		Map<String, Long> uncreditedBySkill = new LinkedHashMap<>();
		if (stored.uncreditedXpBySkill != null)
		{
			for (Map.Entry<String, Long> e : stored.uncreditedXpBySkill.entrySet())
			{
				if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null)
				{
					continue;
				}
				long remainder = Math.max(0L, e.getValue());
				if (remainder > 0L)
				{
					uncreditedBySkill.put(e.getKey(), remainder);
				}
			}
		}
		else
		{
			long legacyUncredited = stored.uncreditedXp == null ? 0L : Math.max(0L, stored.uncreditedXp);
			if (legacyUncredited > 0L)
			{
				log.warn(
					"Discarding legacy uncreditedXp remainder ({}); cannot attribute to a skill",
					legacyUncredited);
			}
		}
		return SkillCreditBaseline.of(xp, uncreditedBySkill);
	}

	private static SerializedSkillCreditBaseline serializeSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		SkillCreditBaseline b = baseline == null ? SkillCreditBaseline.absent() : baseline;
		SerializedSkillCreditBaseline out = new SerializedSkillCreditBaseline();
		if (!b.isPresent())
		{
			// Persist schema fields for missing/absent baselines (upgrade older profiles).
			out.skillXp = new LinkedHashMap<>();
			out.uncreditedXpBySkill = new LinkedHashMap<>();
			return out;
		}

		out.skillXp = new LinkedHashMap<>(b.getSkillXpByName());
		out.uncreditedXpBySkill = new LinkedHashMap<>(b.getUncreditedXpBySkill());
		return out;
	}

	private static class SerializedState
	{
		private int schemaVersion = TcgState.CURRENT_SCHEMA_VERSION;
		private long credits;
		private long openedPacks;
		private List<CardEntry> cardEntries;
		private List<SerializedInstance> cardInstances;
		private Boolean debugLogging;
		private Double packRevealOverlayScale;
		private SerializedSkillCreditBaseline skillCreditBaseline;
		private Long totalCreditsGained;
		private Long profileCreatedAtUnix;
		private Long profileSavedAtUnix;
		private Long cloudRevision;
		private String cloudStateHash;
		/** Last pack-open hiscores ranks (length 6); omitted when unknown. */
		private int[] sidebarRanks;
	}

	private static class SerializedSkillCreditBaseline
	{
		private Map<String, Integer> skillXp;
		private Map<String, Long> uncreditedXpBySkill;
		/** Legacy single-pool remainder; read-only for migration. */
		private Long uncreditedXp;
	}

	/** Legacy schema: one row per owned copy. */
	private static class SerializedInstance
	{
		private String id;
		private String cardName;
		private boolean foil;
		private String pulledBy;
		private long pulledAt;
		private boolean locked;
		private Double condition;
		private Boolean beta;
		private String source;
	}
}

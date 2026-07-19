package com.osrstcg.persist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrstcg.model.CollectionState;
import com.osrstcg.model.EconomyState;
import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.RewardTuningState;
import com.osrstcg.model.SkillCreditBaseline;
import com.osrstcg.model.TcgState;
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
		List<OwnedCardInstance> rows = new ArrayList<>();
		if (stored.cardInstances != null)
		{
			for (SerializedInstance row : stored.cardInstances)
			{
				if (row == null || row.cardName == null || row.cardName.trim().isEmpty())
				{
					continue;
				}
				String id = row.id == null || row.id.trim().isEmpty() ? null : row.id.trim();
				String by = row.pulledBy == null ? "" : row.pulledBy;
				long at = row.pulledAt <= 0L ? 0L : row.pulledAt;
				rows.add(new OwnedCardInstance(id, row.cardName.trim(), row.foil, by, at, row.locked));
			}
		}
		CollectionState coll = CollectionState.copyOf(rows);

		RewardTuningState tuning = RewardTuningState.mergeSerialized(
			stored.foilChancePercent,
			stored.killCreditMultiplier,
			stored.levelUpCreditMultiplier,
			stored.xpCreditMultiplier);

		boolean debug = Boolean.TRUE.equals(stored.debugLogging);
		double packZoom = stored.packRevealOverlayScale == null
			? 1.0d
			: PackRevealZoomUtil.clamp(stored.packRevealOverlayScale);
		int albumW = stored.albumWindowWidth == null ? 0 : stored.albumWindowWidth;
		int albumH = stored.albumWindowHeight == null ? 0 : stored.albumWindowHeight;
		SkillCreditBaseline skillBaseline = parseSkillCreditBaseline(stored.skillCreditBaseline);

		// Always materialize the current schema (upgrades older profiles that lack skill snapshots).
		return new TcgState(
			TcgState.CURRENT_SCHEMA_VERSION,
			new EconomyState(stored.credits, stored.openedPacks),
			coll,
			tuning,
			debug,
			packZoom,
			albumW,
			albumH,
			skillBaseline
		);
	}

	public String toJson(TcgState state)
	{
		TcgState s = Objects.requireNonNullElse(state, TcgState.empty());
		SerializedState serialized = new SerializedState();
		serialized.schemaVersion = TcgState.CURRENT_SCHEMA_VERSION;
		serialized.credits = s.getEconomyState().getCredits();
		serialized.openedPacks = s.getEconomyState().getOpenedPacks();
		serialized.cardInstances = new ArrayList<>();

		RewardTuningState tuning = s.getRewardTuning();
		serialized.foilChancePercent = tuning.getFoilChancePercent();
		serialized.killCreditMultiplier = tuning.getKillCreditMultiplier();
		serialized.levelUpCreditMultiplier = tuning.getLevelUpCreditMultiplier();
		serialized.xpCreditMultiplier = tuning.getXpCreditMultiplier();
		serialized.debugLogging = s.isDebugLogging();
		serialized.packRevealOverlayScale = s.getPackRevealOverlayScale();
		serialized.albumWindowWidth = s.getAlbumWindowWidth();
		serialized.albumWindowHeight = s.getAlbumWindowHeight();
		serialized.skillCreditBaseline = serializeSkillCreditBaseline(s.getSkillCreditBaseline());

		for (OwnedCardInstance inst : s.getCollectionState().getOwnedInstances())
		{
			SerializedInstance row = new SerializedInstance();
			row.id = inst.getInstanceId();
			row.cardName = inst.getCardName();
			row.foil = inst.isFoil();
			row.pulledBy = inst.getPulledByUsername();
			row.pulledAt = inst.getPulledAtEpochMs();
			row.locked = inst.isLocked();
			serialized.cardInstances.add(row);
		}

		return gson.toJson(serialized);
	}

	private static SkillCreditBaseline parseSkillCreditBaseline(SerializedSkillCreditBaseline stored)
	{
		if (stored == null)
		{
			return SkillCreditBaseline.missing();
		}
		// Empty placeholder written during schema upgrade — no retro awards until first settle capture.
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
		long uncredited = stored.uncreditedXp == null ? 0L : Math.max(0L, stored.uncreditedXp);
		return SkillCreditBaseline.of(xp, uncredited);
	}

	private static SerializedSkillCreditBaseline serializeSkillCreditBaseline(SkillCreditBaseline baseline)
	{
		SkillCreditBaseline b = baseline == null ? SkillCreditBaseline.absent() : baseline;
		SerializedSkillCreditBaseline out = new SerializedSkillCreditBaseline();
		if (!b.isPresent())
		{
			// Persist schema fields for missing/absent baselines (upgrade older profiles).
			out.skillXp = new LinkedHashMap<>();
			out.uncreditedXp = 0L;
			return out;
		}

		out.skillXp = new LinkedHashMap<>(b.getSkillXpByName());
		out.uncreditedXp = b.getUncreditedXp();
		return out;
	}

	private static class SerializedState
	{
		private int schemaVersion = TcgState.CURRENT_SCHEMA_VERSION;
		private long credits;
		private long openedPacks;
		private List<SerializedInstance> cardInstances;
		private Integer foilChancePercent;
		private Double killCreditMultiplier;
		private Double levelUpCreditMultiplier;
		private Double xpCreditMultiplier;
		private Boolean debugLogging;
		private Double packRevealOverlayScale;
		private Integer albumWindowWidth;
		private Integer albumWindowHeight;
		private SerializedSkillCreditBaseline skillCreditBaseline;
	}

	private static class SerializedSkillCreditBaseline
	{
		private Map<String, Integer> skillXp;
		private Long uncreditedXp;
	}

	private static class SerializedInstance
	{
		private String id;
		private String cardName;
		private boolean foil;
		private String pulledBy;
		private long pulledAt;
		private boolean locked;
	}
}

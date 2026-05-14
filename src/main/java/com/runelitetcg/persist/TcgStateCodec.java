package com.runelitetcg.persist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.runelitetcg.model.CollectionState;
import com.runelitetcg.model.EconomyState;
import com.runelitetcg.model.OwnedCardInstance;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.model.TcgState;
import com.runelitetcg.util.PackRevealZoomUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

	public TcgState fromJson(String rawState)
	{
		try
		{
			String json = Objects.requireNonNullElse(rawState, "");
			SerializedState stored = gson.fromJson(json, SerializedState.class);
			if (stored == null)
			{
				return TcgState.empty();
			}

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
					rows.add(new OwnedCardInstance(id, row.cardName.trim(), row.foil, by, at));
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

			return new TcgState(
				TcgState.CURRENT_SCHEMA_VERSION,
				new EconomyState(stored.credits, stored.openedPacks),
				coll,
				tuning,
				debug,
				packZoom
			);
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Failed to deserialize OSRS TCG state, falling back to defaults", ex);
			return TcgState.empty();
		}
	}

	public String toJson(TcgState state)
	{
		TcgState s = Objects.requireNonNullElse(state, TcgState.empty());
		SerializedState serialized = new SerializedState();
		serialized.schemaVersion = s.getSchemaVersion();
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

		for (OwnedCardInstance inst : s.getCollectionState().getOwnedInstances())
		{
			SerializedInstance row = new SerializedInstance();
			row.id = inst.getInstanceId();
			row.cardName = inst.getCardName();
			row.foil = inst.isFoil();
			row.pulledBy = inst.getPulledByUsername();
			row.pulledAt = inst.getPulledAtEpochMs();
			serialized.cardInstances.add(row);
		}

		return gson.toJson(serialized);
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
	}

	private static class SerializedInstance
	{
		private String id;
		private String cardName;
		private boolean foil;
		private String pulledBy;
		private long pulledAt;
	}
}

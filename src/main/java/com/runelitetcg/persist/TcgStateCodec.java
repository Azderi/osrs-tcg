package com.runelitetcg.persist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.runelitetcg.model.CardCollectionKey;
import com.runelitetcg.model.CollectionState;
import com.runelitetcg.model.EconomyState;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.model.TcgState;
import com.runelitetcg.util.PackRevealZoomUtil;
import java.util.HashMap;
import java.util.Map;
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

			Map<CardCollectionKey, Integer> collection = new HashMap<>();
			Map<CardCollectionKey, Long> lastObtained = new HashMap<>();
			if (stored.collection != null)
			{
				for (Map.Entry<String, Integer> entry : stored.collection.entrySet())
				{
					CardCollectionKey key = deserializeCollectionKey(entry.getKey());
					if (key == null)
					{
						continue;
					}

					Integer quantity = entry.getValue();
					if (quantity != null && quantity > 0)
					{
						collection.put(key, quantity);
						if (stored.collectionLastObtained != null)
						{
							Long timestamp = stored.collectionLastObtained.get(entry.getKey());
							if (timestamp != null && timestamp > 0)
							{
								lastObtained.put(key, timestamp);
							}
						}
					}
				}
			}

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
				new CollectionState(collection, lastObtained),
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
		serialized.collection = new HashMap<>();
		serialized.collectionLastObtained = new HashMap<>();

		RewardTuningState tuning = s.getRewardTuning();
		serialized.foilChancePercent = tuning.getFoilChancePercent();
		serialized.killCreditMultiplier = tuning.getKillCreditMultiplier();
		serialized.levelUpCreditMultiplier = tuning.getLevelUpCreditMultiplier();
		serialized.xpCreditMultiplier = tuning.getXpCreditMultiplier();
		serialized.debugLogging = s.isDebugLogging();
		serialized.packRevealOverlayScale = s.getPackRevealOverlayScale();

		for (Map.Entry<CardCollectionKey, Integer> entry : s.getCollectionState().getOwnedCards().entrySet())
		{
			String key = serializeCollectionKey(entry.getKey());
			serialized.collection.put(key, entry.getValue());
			long ts = s.getCollectionState().getLastObtainedAt(entry.getKey());
			if (ts > 0)
			{
				serialized.collectionLastObtained.put(key, ts);
			}
		}

		return gson.toJson(serialized);
	}

	private static String serializeCollectionKey(CardCollectionKey key)
	{
		return key.getCardName() + '|' + (char) ('0' + Boolean.compare(key.isFoil(), false));
	}

	private static CardCollectionKey deserializeCollectionKey(String rawKey)
	{
		if (rawKey == null || rawKey.isEmpty())
		{
			return null;
		}

		int separator = rawKey.lastIndexOf('|');
		if (separator <= 0 || separator >= rawKey.length() - 1)
		{
			return null;
		}

		String name = rawKey.substring(0, separator);
		String foilFlag = rawKey.substring(separator + 1);
		if (name.isEmpty())
		{
			return null;
		}

		boolean foil = foilFlag.length() == 1 && foilFlag.charAt(0) == '1';
		return new CardCollectionKey(name, foil);
	}

	private static class SerializedState
	{
		private int schemaVersion = TcgState.CURRENT_SCHEMA_VERSION;
		private long credits;
		private long openedPacks;
		private Map<String, Integer> collection;
		private Map<String, Long> collectionLastObtained;
		private Integer foilChancePercent;
		private Double killCreditMultiplier;
		private Double levelUpCreditMultiplier;
		private Double xpCreditMultiplier;
		private Boolean debugLogging;
		private Double packRevealOverlayScale;
	}
}

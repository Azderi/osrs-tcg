package com.osrstcg.cloud.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcg.cloud.api.JsonObjects;
import com.osrstcg.state.OwnedCardInstance;
import com.osrstcg.state.TcgStateService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Applies membership ops from {@code GET /me/collection/changes}; lock/unlock ignored. */
final class CloudCollectionOpsApplier
{
	private CloudCollectionOpsApplier()
	{
	}

	/**
	 * False if empty/incomplete/unknown, or only non-objects. lock-only succeeds.
	 * Validates the full batch before mutating so a late failure leaves state unchanged.
	 */
	static boolean applyOps(JsonElement opsEl, TcgStateService stateService)
	{
		if (opsEl == null || !opsEl.isJsonArray() || stateService == null)
		{
			return false;
		}
		JsonArray ops = opsEl.getAsJsonArray();
		if (ops.size() == 0)
		{
			return false;
		}
		List<PlannedOp> planned = new ArrayList<>();
		int seen = 0;
		for (JsonElement el : ops)
		{
			if (el == null || !el.isJsonObject())
			{
				continue;
			}
			seen++;
			JsonObject op = el.getAsJsonObject();
			String kind = JsonObjects.textTrimmed(op, "op");
			if (kind == null)
			{
				return false;
			}
			if ("lock".equals(kind) || "unlock".equals(kind))
			{
				continue;
			}
			String id = JsonObjects.textTrimmed(op, "instanceId");
			if ("remove".equals(kind) || "sold".equals(kind))
			{
				if (id == null)
				{
					return false;
				}
				planned.add(PlannedOp.remove(id));
				continue;
			}
			if (!"add".equals(kind) && !"create".equals(kind))
			{
				return false;
			}
			if (!op.has("card") || !op.get("card").isJsonObject())
			{
				return false;
			}
			JsonObject cardJson = op.getAsJsonObject("card");
			if (id == null)
			{
				id = JsonObjects.textTrimmed(cardJson, "instanceId");
			}
			if (id == null)
			{
				return false;
			}
			JsonArray wrap = new JsonArray();
			wrap.add(cardJson);
			List<OwnedCardInstance> parsed = CloudPlayerStateParser.parseCards(wrap);
			if (parsed.isEmpty())
			{
				return false;
			}
			OwnedCardInstance c = parsed.get(0);
			planned.add(PlannedOp.add(new OwnedCardInstance(
				id, c.getCardName(), c.isFoil(), c.getPulledByUsername(), c.getPulledAtEpochMs())));
		}
		if (seen == 0)
		{
			return false;
		}
		for (PlannedOp plannedOp : planned)
		{
			plannedOp.apply(stateService);
		}
		return true;
	}

	private static final class PlannedOp
	{
		private final String removeId;
		private final OwnedCardInstance add;

		private PlannedOp(String removeId, OwnedCardInstance add)
		{
			this.removeId = removeId;
			this.add = add;
		}

		static PlannedOp remove(String id)
		{
			return new PlannedOp(id, null);
		}

		static PlannedOp add(OwnedCardInstance instance)
		{
			return new PlannedOp(instance.getInstanceId(), instance);
		}

		void apply(TcgStateService stateService)
		{
			if (removeId != null)
			{
				stateService.removeOwnedCardInstances(Collections.singleton(removeId));
			}
			if (add != null)
			{
				stateService.addOwnedCardInstances(List.of(add));
			}
		}
	}
}

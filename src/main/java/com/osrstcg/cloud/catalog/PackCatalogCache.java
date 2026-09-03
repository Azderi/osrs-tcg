package com.osrstcg.cloud.catalog;

import com.osrstcg.catalog.BoosterPackDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory snapshot of {@code GET /api/v1/packs}. Empty when disconnected. */
public final class PackCatalogCache
{
	private final String catalogVersion;
	private final int packSize;
	private final List<BoosterPackDefinition> packs;
	private final boolean fromServer;

	/**
	 * @param packSize clamped to a minimum of 0.
	 * @param packs defensively copied into an unmodifiable list; null treated as empty.
	 * @param fromServer whether this cache reflects an actual server fetch, vs. the empty placeholder.
	 */
	public PackCatalogCache(
		String catalogVersion,
		int packSize,
		List<BoosterPackDefinition> packs,
		boolean fromServer)
	{
		this.catalogVersion = catalogVersion == null ? "" : catalogVersion;
		this.packSize = Math.max(0, packSize);
		this.packs = packs == null
			? List.of()
			: Collections.unmodifiableList(List.copyOf(packs));
		this.fromServer = fromServer;
	}

	/** Server-reported catalog version, or {@code ""} if unknown. */
	public String getCatalogVersion()
	{
		return catalogVersion;
	}

	/** Number of cards in a booster pack per this catalog. */
	public int getPackSize()
	{
		return packSize;
	}

	/** Unmodifiable list of packs in this catalog. */
	public List<BoosterPackDefinition> getPacks()
	{
		return packs;
	}

	/** True when this cache was populated from an actual server response, as opposed to the empty placeholder. */
	public boolean isFromServer()
	{
		return fromServer;
	}

	/** True when this catalog has no packs. */
	public boolean isEmpty()
	{
		return packs.isEmpty();
	}

	/** Builds a lookup map keyed by trimmed pack id, keeping the first pack seen for any duplicate id. */
	public Map<String, BoosterPackDefinition> byId()
	{
		Map<String, BoosterPackDefinition> map = new LinkedHashMap<>();
		for (BoosterPackDefinition pack : packs)
		{
			if (pack == null || pack.getId() == null || pack.getId().isBlank())
			{
				continue;
			}
			map.putIfAbsent(pack.getId().trim(), pack);
		}
		return Collections.unmodifiableMap(map);
	}

	/** Looks up a pack by id (via {@link #byId()}); empty when {@code packId} is null/blank or not found. */
	public Optional<BoosterPackDefinition> get(String packId)
	{
		if (packId == null || packId.isBlank())
		{
			return Optional.empty();
		}
		return Optional.ofNullable(byId().get(packId.trim()));
	}
}

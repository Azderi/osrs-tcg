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

	public String getCatalogVersion()
	{
		return catalogVersion;
	}

	public int getPackSize()
	{
		return packSize;
	}

	public List<BoosterPackDefinition> getPacks()
	{
		return packs;
	}

	public boolean isFromServer()
	{
		return fromServer;
	}

	public boolean isEmpty()
	{
		return packs.isEmpty();
	}

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

	public Optional<BoosterPackDefinition> get(String packId)
	{
		if (packId == null || packId.isBlank())
		{
			return Optional.empty();
		}
		return Optional.ofNullable(byId().get(packId.trim()));
	}
}

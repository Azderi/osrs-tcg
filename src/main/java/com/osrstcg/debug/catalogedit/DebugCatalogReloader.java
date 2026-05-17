package com.osrstcg.debug.catalogedit;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.service.PackRevealService;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;

@Singleton
@Slf4j
final class DebugCatalogReloader
{
	private final CardDatabase cardDatabase;
	private final DebugCardJsonPaths paths;
	private final DebugCatalogRefreshBroadcaster refreshBroadcaster;
	private final PackRevealService packRevealService;
	private final Client client;

	@Inject
	DebugCatalogReloader(
		CardDatabase cardDatabase,
		DebugCardJsonPaths paths,
		DebugCatalogRefreshBroadcaster refreshBroadcaster,
		PackRevealService packRevealService,
		Client client)
	{
		this.cardDatabase = cardDatabase;
		this.paths = paths;
		this.refreshBroadcaster = refreshBroadcaster;
		this.packRevealService = packRevealService;
		this.client = client;
	}

	void reloadEntireCatalog()
	{
		Optional<Path> workspace = paths.resolveWorkspaceCardJson();
		cardDatabase.forceReloadForDebug(workspace.orElse(null));
		int count = cardDatabase.size();
		log.info("DEBUG_CARD_EDIT: reloaded card catalog ({} cards, source={})",
			count, workspace.map(Path::toString).orElse("classpath"));

		packRevealService.refreshAfterCatalogReload();
		refreshBroadcaster.fireAfterCatalogReload();

		if (client != null && client.getGameState() != null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				String.format(Locale.US, "[OSRS TCG] Card catalog reloaded (%d cards).", count), null);
		}
	}
}

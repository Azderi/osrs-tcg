package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.PackCatalog;
import com.osrstcg.debug.catalogedit.DebugCardCatalogEditFacade;
import com.osrstcg.debug.catalogedit.DebugCatalogRefreshBroadcaster;
import com.osrstcg.service.CardPartyTransferService;
import com.osrstcg.service.TcgStateService;
import com.osrstcg.service.WikiImageCacheService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.party.PartyService;

@Singleton
public final class CollectionAlbumManager
{
	private final CardDatabase cardDatabase;
	private final TcgStateService stateService;
	private final PackCatalog packCatalog;
	private final WikiImageCacheService imageCacheService;
	private final PartyService partyService;
	private final CardPartyTransferService cardPartyTransferService;
	private final DebugCardCatalogEditFacade debugCardCatalogEditFacade;

	private volatile CollectionAlbumWindow window;

	@Inject
	public CollectionAlbumManager(
		CardDatabase cardDatabase,
		TcgStateService stateService,
		PackCatalog packCatalog,
		WikiImageCacheService imageCacheService,
		PartyService partyService,
		CardPartyTransferService cardPartyTransferService,
		DebugCardCatalogEditFacade debugCardCatalogEditFacade,
		DebugCatalogRefreshBroadcaster debugCatalogRefreshBroadcaster)
	{
		this.cardDatabase = cardDatabase;
		this.stateService = stateService;
		this.packCatalog = packCatalog;
		this.imageCacheService = imageCacheService;
		this.partyService = partyService;
		this.cardPartyTransferService = cardPartyTransferService;
		this.debugCardCatalogEditFacade = debugCardCatalogEditFacade;
		// DEBUG_CARD_EDIT: register without pulling this manager into DebugCatalogReloader (avoids Guice cycle).
		debugCatalogRefreshBroadcaster.register(this::refreshAfterCatalogReload);
	}

	public void showOrBringToFront()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (window == null || !window.isDisplayable())
			{
				window = new CollectionAlbumWindow(
					cardDatabase, stateService, packCatalog, imageCacheService, partyService,
					cardPartyTransferService, debugCardCatalogEditFacade);
			}
			window.refreshData();
			window.prepareToShow();
			window.setVisible(true);
			window.toFront();
		});
	}

	public void refreshIfVisible()
	{
		SwingUtilities.invokeLater(() ->
		{
			CollectionAlbumWindow w = window;
			if (w != null && w.isShowing())
			{
				w.rebuildModel();
			}
		});
	}

	/** DEBUG_CARD_EDIT: full catalog refresh (tabs, rarity table, grid) after Card.json edit. */
	public void refreshAfterCatalogReload()
	{
		SwingUtilities.invokeLater(() ->
		{
			CollectionAlbumWindow w = window;
			if (w != null && w.isDisplayable())
			{
				w.refreshData();
			}
		});
	}

	public void dispose()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (window != null)
			{
				window.disposeInternal();
				window = null;
			}
		});
	}
}

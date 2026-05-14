package com.runelitetcg.ui.collectionalbum;

import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.data.PackCatalog;
import com.runelitetcg.service.CardPartyTransferService;
import com.runelitetcg.service.TcgStateService;
import com.runelitetcg.service.WikiImageCacheService;
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

	private volatile CollectionAlbumWindow window;

	@Inject
	public CollectionAlbumManager(
		CardDatabase cardDatabase,
		TcgStateService stateService,
		PackCatalog packCatalog,
		WikiImageCacheService imageCacheService,
		PartyService partyService,
		CardPartyTransferService cardPartyTransferService)
	{
		this.cardDatabase = cardDatabase;
		this.stateService = stateService;
		this.packCatalog = packCatalog;
		this.imageCacheService = imageCacheService;
		this.partyService = partyService;
		this.cardPartyTransferService = cardPartyTransferService;
	}

	public void showOrBringToFront()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (window == null || !window.isDisplayable())
			{
				window = new CollectionAlbumWindow(
					cardDatabase, stateService, packCatalog, imageCacheService, partyService, cardPartyTransferService);
			}
			window.refreshData();
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

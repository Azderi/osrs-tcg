package com.osrstcg.ui.collectionalbum;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.PackCatalog;
import com.osrstcg.service.CardPartyTradeService;
import com.osrstcg.service.CardPartyTransferService;
import com.osrstcg.service.TcgStateService;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.TcgPanel;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
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
	private final CardPartyTradeService cardPartyTradeService;
	private final Provider<TcgPanel> tcgPanelProvider;

	private volatile CollectionAlbumWindow window;
	private Timer collectionRefreshDebounceTimer;

	@Inject
	public CollectionAlbumManager(
		CardDatabase cardDatabase,
		TcgStateService stateService,
		PackCatalog packCatalog,
		WikiImageCacheService imageCacheService,
		PartyService partyService,
		CardPartyTransferService cardPartyTransferService,
		CardPartyTradeService cardPartyTradeService,
		Provider<TcgPanel> tcgPanelProvider)
	{
		this.cardDatabase = cardDatabase;
		this.stateService = stateService;
		this.packCatalog = packCatalog;
		this.imageCacheService = imageCacheService;
		this.partyService = partyService;
		this.cardPartyTransferService = cardPartyTransferService;
		this.cardPartyTradeService = cardPartyTradeService;
		this.tcgPanelProvider = tcgPanelProvider;
	}

	public void showOrBringToFront()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (window == null || !window.isDisplayable())
			{
				window = new CollectionAlbumWindow(
					cardDatabase, stateService, packCatalog, imageCacheService, partyService,
					cardPartyTransferService, cardPartyTradeService, this::refreshSidebarIfVisible);
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

	public void refreshPartyTradeUiIfVisible()
	{
		SwingUtilities.invokeLater(() ->
		{
			CollectionAlbumWindow w = window;
			if (w != null && w.isShowing())
			{
				w.refreshPartyTradeUi();
			}
		});
	}

	private void refreshSidebarIfVisible()
	{
		TcgPanel panel = tcgPanelProvider.get();
		if (panel != null)
		{
			panel.refresh();
		}
	}

	public void dispose()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (collectionRefreshDebounceTimer != null)
			{
				collectionRefreshDebounceTimer.stop();
				collectionRefreshDebounceTimer = null;
			}
			if (window != null)
			{
				window.disposeInternal();
				window = null;
			}
		});
	}
}

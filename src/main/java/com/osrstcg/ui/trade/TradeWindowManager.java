package com.osrstcg.ui.trade;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.service.CardPartyTradeService;
import com.osrstcg.service.WikiImageCacheService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

@Singleton
public final class TradeWindowManager
{
	private final CardDatabase cardDatabase;
	private final WikiImageCacheService imageCacheService;
	private final CardPartyTradeService tradeService;

	private volatile TradeWindow window;

	@Inject
	public TradeWindowManager(
		CardDatabase cardDatabase,
		WikiImageCacheService imageCacheService,
		CardPartyTradeService tradeService)
	{
		this.cardDatabase = cardDatabase;
		this.imageCacheService = imageCacheService;
		this.tradeService = tradeService;
	}

	public void showOrBringToFront()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (window == null || !window.isDisplayable())
			{
				window = new TradeWindow(cardDatabase, imageCacheService, tradeService);
			}
			window.prepareToShow();
			window.setVisible(true);
			window.toFront();
		});
	}

	public void refreshIfVisible()
	{
		SwingUtilities.invokeLater(() ->
		{
			TradeWindow w = window;
			if (tradeService.isTradeActive())
			{
				if (w != null && w.isShowing())
				{
					w.refreshFromService();
				}
				return;
			}
			if (w != null && w.isShowing())
			{
				w.hideWithoutCancel();
			}
		});
	}

	public void hide()
	{
		SwingUtilities.invokeLater(() ->
		{
			TradeWindow w = window;
			if (w != null)
			{
				w.hideWithoutCancel();
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

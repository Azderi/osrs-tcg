package com.osrstcg.debug.catalogedit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * DEBUG_CARD_EDIT: breaks Guice cycles by notifying catalog consumers without injecting them into
 * {@link DebugCatalogReloader}. Delete with the debug card editor package.
 */
@Singleton
public final class DebugCatalogRefreshBroadcaster
{
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	@Inject
	DebugCatalogRefreshBroadcaster()
	{
	}

	public void register(Runnable listener)
	{
		if (listener != null)
		{
			listeners.add(listener);
		}
	}

	void fireAfterCatalogReload()
	{
		SwingUtilities.invokeLater(() ->
		{
			for (Runnable listener : listeners)
			{
				try
				{
					listener.run();
				}
				catch (RuntimeException ex)
				{
					// keep other listeners running
				}
			}
		});
	}
}

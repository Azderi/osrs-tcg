package com.osrstcg.state;

import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

/** Listener lists for {@link TcgStateService} (no extra lock). */
@Slf4j
final class TcgStateNotifier
{
	private final CopyOnWriteArrayList<Runnable> stateChangeListeners = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<Runnable> ownedCollectionListeners = new CopyOnWriteArrayList<>();

	void addStateChangeListener(Runnable listener)
	{
		if (listener != null)
		{
			stateChangeListeners.addIfAbsent(listener);
		}
	}

	void removeStateChangeListener(Runnable listener)
	{
		if (listener != null)
		{
			stateChangeListeners.remove(listener);
		}
	}

	void addOwnedCollectionListener(Runnable listener)
	{
		if (listener != null)
		{
			ownedCollectionListeners.addIfAbsent(listener);
		}
	}

	void removeOwnedCollectionListener(Runnable listener)
	{
		if (listener != null)
		{
			ownedCollectionListeners.remove(listener);
		}
	}

	void notifyStateChangeListeners()
	{
		runListeners(stateChangeListeners, "State change listener failed");
	}

	void notifyOwnedCollectionListeners()
	{
		runListeners(ownedCollectionListeners, "Owned collection listener failed");
	}

	/** Collection instances changed - notify UI and owned-names interop. */
	void notifyCollectionMutated()
	{
		notifyStateChangeListeners();
		notifyOwnedCollectionListeners();
	}

	private void runListeners(CopyOnWriteArrayList<Runnable> listeners, String failLog)
	{
		for (Runnable notify : listeners)
		{
			try
			{
				notify.run();
			}
			catch (Exception ex)
			{
				log.debug(failLog, ex);
			}
		}
	}
}

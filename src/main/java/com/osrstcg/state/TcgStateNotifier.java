package com.osrstcg.state;

import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
/** Listener lists for {@link TcgStateService} (no extra lock). */
@Slf4j
final class TcgStateNotifier
{
	private final CopyOnWriteArrayList<Runnable> stateChangeListeners = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<Runnable> ownedCollectionListeners = new CopyOnWriteArrayList<>();
/** Registers a listener for general state changes; ignored if null or already registered. */
	void addStateChangeListener(Runnable listener)
	{
		if (listener != null)
		{
			stateChangeListeners.addIfAbsent(listener);
		}
	}
/** Unregisters a previously-added state change listener; no-op if null or not registered. */
	void removeStateChangeListener(Runnable listener)
	{
		if (listener != null)
		{
			stateChangeListeners.remove(listener);
		}
	}
/** Registers a listener for owned-collection changes; ignored if null or already registered. */
	void addOwnedCollectionListener(Runnable listener)
	{
		if (listener != null)
		{
			ownedCollectionListeners.addIfAbsent(listener);
		}
	}
/** Unregisters a previously-added owned-collection listener; no-op if null or not registered. */
	void removeOwnedCollectionListener(Runnable listener)
	{
		if (listener != null)
		{
			ownedCollectionListeners.remove(listener);
		}
	}
/** Runs all registered state change listeners, logging but not propagating failures. */
	void notifyStateChangeListeners()
	{
		runListeners(stateChangeListeners, "State change listener failed");
	}
/** Runs all registered owned-collection listeners, logging but not propagating failures. */
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
/** Invokes each listener, catching and debug-logging any exception so one bad listener doesn't block the rest. */
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

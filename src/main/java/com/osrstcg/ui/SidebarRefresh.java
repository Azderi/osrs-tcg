package com.osrstcg.ui;

import com.google.inject.ImplementedBy;
/**
 * Sidebar refresh callbacks exposed by {@link TcgPanel} to callers (e.g. {@code PackOpenCoordinator})
 * that need to update the panel without depending on it directly. All methods mutate Swing state and
 * must be called on the EDT.
 */
@ImplementedBy(TcgPanel.class)
public interface SidebarRefresh
{
/** Rebuilds/refreshes the currently visible tab's content. */
	void refresh();
/** Refreshes just the displayed credits balance. */
	void refreshCredits();
/** Freezes sidebar interaction while a pack reveal is in progress. */
	void beginPackRevealSidebarFreeze();
/** Lifts the freeze applied by {@link #beginPackRevealSidebarFreeze()}. */
	void clearPackRevealSidebarFreeze();
/** Refreshes the sidebar after a pack reveal overlay closes. */
	void refreshAfterPackRevealClose();
}

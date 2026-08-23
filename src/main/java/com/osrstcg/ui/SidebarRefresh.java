package com.osrstcg.ui;

import com.google.inject.ImplementedBy;

/**
 * Sidebar UI port so pack/credit/cloud code can refresh without depending on {@link TcgPanel}.
 */
@ImplementedBy(TcgPanel.class)
public interface SidebarRefresh
{
	void refresh();

	/** Update credit labels in place without rebuilding tabs. */
	void refreshCredits();

	void beginPackRevealSidebarFreeze();

	void clearPackRevealSidebarFreeze();

	void refreshAfterPackRevealClose();
}

package com.osrstcg.ui;

import com.google.inject.ImplementedBy;

@ImplementedBy(TcgPanel.class)
public interface SidebarRefresh
{
	void refresh();

	void refreshCredits();

	void beginPackRevealSidebarFreeze();

	void clearPackRevealSidebarFreeze();

	void refreshAfterPackRevealClose();
}

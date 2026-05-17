package com.osrstcg.debug.catalogedit;

import com.osrstcg.service.TcgStateService;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
final class DebugCardEditGate
{
	private final TcgStateService stateService;

	@Inject
	DebugCardEditGate(TcgStateService stateService)
	{
		this.stateService = stateService;
	}

	boolean isEnabled()
	{
		return stateService.isDebugLogging();
	}
}

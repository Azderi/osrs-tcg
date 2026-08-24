package com.osrstcg.persist;

import com.osrstcg.state.TcgState;

public final class TcgStateLoadResult
{
	private final TcgState state;
	private final TcgStateLoadSource source;
	private final boolean diskLoadFailed;
	private final boolean debugResetOnLoad;

	public TcgStateLoadResult(TcgState state, TcgStateLoadSource source)
	{
		this(state, source, false, false);
	}

	public TcgStateLoadResult(
		TcgState state,
		TcgStateLoadSource source,
		boolean diskLoadFailed,
		boolean debugResetOnLoad)
	{
		this.state = state == null ? TcgState.empty() : state;
		this.source = source == null ? TcgStateLoadSource.EMPTY : source;
		this.diskLoadFailed = diskLoadFailed;
		this.debugResetOnLoad = debugResetOnLoad;
	}

	public TcgState getState()
	{
		return state;
	}

	public TcgStateLoadSource getSource()
	{
		return source;
	}

	public boolean isDiskLoadFailed()
	{
		return diskLoadFailed;
	}

	public boolean isDebugResetOnLoad()
	{
		return debugResetOnLoad;
	}
}

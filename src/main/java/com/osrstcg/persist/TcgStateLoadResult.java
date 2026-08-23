package com.osrstcg.persist;

import com.osrstcg.state.TcgState;

public final class TcgStateLoadResult
{
	private final TcgState state;
	private final TcgStateLoadSource source;
	private final boolean configLoadFailed;
	private final boolean diskLoadFailed;
	private final boolean debugResetOnLoad;

	public TcgStateLoadResult(TcgState state, TcgStateLoadSource source)
	{
		this(state, source, false, false, false);
	}

	public TcgStateLoadResult(
		TcgState state,
		TcgStateLoadSource source,
		boolean configLoadFailed,
		boolean diskLoadFailed)
	{
		this(state, source, configLoadFailed, diskLoadFailed, false);
	}

	public TcgStateLoadResult(
		TcgState state,
		TcgStateLoadSource source,
		boolean configLoadFailed,
		boolean diskLoadFailed,
		boolean debugResetOnLoad)
	{
		this.state = state == null ? TcgState.empty() : state;
		this.source = source == null ? TcgStateLoadSource.EMPTY : source;
		this.configLoadFailed = configLoadFailed;
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

	public boolean isConfigLoadFailed()
	{
		return configLoadFailed;
	}

	public boolean isDiskLoadFailed()
	{
		return diskLoadFailed;
	}

	public boolean isAllBackupsFailed()
	{
		return configLoadFailed && source == TcgStateLoadSource.EMPTY;
	}

	public boolean isDebugResetOnLoad()
	{
		return debugResetOnLoad;
	}
}

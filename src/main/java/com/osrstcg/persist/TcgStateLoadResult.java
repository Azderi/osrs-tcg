package com.osrstcg.persist;

import com.osrstcg.model.TcgState;

public final class TcgStateLoadResult
{
	private final TcgState state;
	private final TcgStateLoadSource source;
	private final boolean primaryLoadFailed;
	private final boolean configBackupLoadFailed;
	private final boolean fileBackupLoadFailed;
	private final boolean debugResetOnLoad;

	public TcgStateLoadResult(
		TcgState state,
		TcgStateLoadSource source,
		boolean primaryLoadFailed,
		boolean configBackupLoadFailed,
		boolean fileBackupLoadFailed)
	{
		this(state, source, primaryLoadFailed, configBackupLoadFailed, fileBackupLoadFailed, false);
	}

	public TcgStateLoadResult(
		TcgState state,
		TcgStateLoadSource source,
		boolean primaryLoadFailed,
		boolean configBackupLoadFailed,
		boolean fileBackupLoadFailed,
		boolean debugResetOnLoad)
	{
		this.state = state == null ? TcgState.empty() : state;
		this.source = source == null ? TcgStateLoadSource.EMPTY : source;
		this.primaryLoadFailed = primaryLoadFailed;
		this.configBackupLoadFailed = configBackupLoadFailed;
		this.fileBackupLoadFailed = fileBackupLoadFailed;
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

	public boolean isPrimaryLoadFailed()
	{
		return primaryLoadFailed;
	}

	public boolean isConfigBackupLoadFailed()
	{
		return configBackupLoadFailed;
	}

	public boolean isFileBackupLoadFailed()
	{
		return fileBackupLoadFailed;
	}

	public boolean isAllBackupsFailed()
	{
		return primaryLoadFailed && source == TcgStateLoadSource.EMPTY;
	}

	public boolean isDebugResetOnLoad()
	{
		return debugResetOnLoad;
	}
}

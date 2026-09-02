package com.osrstcg.persist;

import com.osrstcg.state.TcgState;

/** Result of a {@link TcgStateStore#load()} call: the resolved state plus where it came from. */
public final class TcgStateLoadResult
{
	private final TcgState state;
	private final TcgStateLoadSource source;

	/** Defaults null {@code state}/{@code source} to {@link TcgState#empty()} and {@link TcgStateLoadSource#EMPTY}. */
	public TcgStateLoadResult(TcgState state, TcgStateLoadSource source)
	{
		this.state = state == null ? TcgState.empty() : state;
		this.source = source == null ? TcgStateLoadSource.EMPTY : source;
	}

	/** The loaded (or empty default) state. */
	public TcgState getState()
	{
		return state;
	}

	/** Where {@link #getState()} was loaded from. */
	public TcgStateLoadSource getSource()
	{
		return source;
	}
}

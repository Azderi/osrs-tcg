package com.osrstcg.persist;

import com.osrstcg.state.TcgState;

public final class TcgStateLoadResult
{
	private final TcgState state;
	private final TcgStateLoadSource source;

	public TcgStateLoadResult(TcgState state, TcgStateLoadSource source)
	{
		this.state = state == null ? TcgState.empty() : state;
		this.source = source == null ? TcgStateLoadSource.EMPTY : source;
	}

	public TcgState getState()
	{
		return state;
	}

	public TcgStateLoadSource getSource()
	{
		return source;
	}
}

package com.osrstcg.persist;
/** Where a loaded {@link TcgState} came from. */
public enum TcgStateLoadSource
{
/** Loaded from the on-disk {@code tcg.save} file. */
	DISK,
/** No valid save was found; an empty default state was used. */
	EMPTY
}

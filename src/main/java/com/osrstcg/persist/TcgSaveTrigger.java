package com.osrstcg.persist;

/** Reason for a local {@code tcg.save} write. */
public enum TcgSaveTrigger
{
	RESET,
	LOGOUT,
	CLIENT_SHUTDOWN,
	PLUGIN_UNLOAD,
	MANUAL
}

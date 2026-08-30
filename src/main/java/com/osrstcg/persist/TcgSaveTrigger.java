package com.osrstcg.persist;

/** Reason for a local {@code tcg.save} write. */
public enum TcgSaveTrigger
{
	COLLECTION_CHANGE,
	RESET,
	LOGOUT,
	CLIENT_SHUTDOWN,
	PLUGIN_UNLOAD,
	MANUAL,
	CLOUD_SYNC,
	UNKNOWN
}

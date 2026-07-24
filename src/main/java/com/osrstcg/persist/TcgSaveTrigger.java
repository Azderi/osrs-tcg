package com.osrstcg.persist;

/**
 * Reason recorded in {@code saves.json} for a disk write.
 */
public enum TcgSaveTrigger
{
	COLLECTION_CHANGE,
	RESET,
	LOGOUT,
	CLIENT_SHUTDOWN,
	PLUGIN_UNLOAD,
	MANUAL,
	MIGRATION,
	LOAD,
	UNKNOWN
}

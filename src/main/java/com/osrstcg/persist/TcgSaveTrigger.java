package com.osrstcg.persist;
/** Reason for a local {@code tcg.save} write. */
public enum TcgSaveTrigger
{
/** Player logged out. */
	LOGOUT,
/** Client is shutting down. */
	CLIENT_SHUTDOWN,
/** Plugin was disabled/unloaded. */
	PLUGIN_UNLOAD
}

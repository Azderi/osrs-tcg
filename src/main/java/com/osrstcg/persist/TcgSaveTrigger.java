package com.osrstcg.persist;

/** Reason for a local {@code tcg.save} write. */
public enum TcgSaveTrigger
{
	/** Triggered by {@code ::tcg-reset}. */
	RESET,
	/** Player logged out. */
	LOGOUT,
	/** Client is shutting down. */
	CLIENT_SHUTDOWN,
	/** Plugin was disabled/unloaded. */
	PLUGIN_UNLOAD,
	/** Explicit save not tied to a lifecycle event. */
	MANUAL
}

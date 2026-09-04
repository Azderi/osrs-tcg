package com.osrstcg.cloud.api;
/** High-level cloud connectivity state for UI display. */
public enum CloudConnectionState
{
/** No active session with the cloud backend. */
	DISCONNECTED,
/** A connection attempt (pairing / token refresh) is in progress. */
	CONNECTING,
/** Authenticated and able to reach the cloud backend. */
	CONNECTED,
/** Last attempt failed. */
	ERROR
}

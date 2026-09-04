package com.osrstcg.cloud.session;

import net.runelite.api.Client;
import net.runelite.client.util.Text;
/** Sanitized local-player RSN with last-known fallback when the client name is unavailable. */
public final class CachedDisplayName
{
	private String last;
/** Reads and sanitizes the local player's name, caching the last known value. */
	public String resolve(Client client)
	{
		if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			String name = Text.sanitize(client.getLocalPlayer().getName());
			if (name != null && !name.isEmpty())
			{
				last = name;
				return name;
			}
		}
		return last;
	}
}

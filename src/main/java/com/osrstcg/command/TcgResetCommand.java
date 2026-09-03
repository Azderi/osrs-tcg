package com.osrstcg.command;

import com.osrstcg.OsrsTcgConfig;
import com.osrstcg.cloud.session.CloudSessionCoordinator;
import com.osrstcg.ui.SidebarRefresh;
import com.osrstcg.util.TcgPluginGameMessages;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;

/**
 * Implements the {@code ::tcg-reset} chat command: clears all plugin config keys, restores defaults,
 * and reconnects the cloud session.
 */
@Singleton
public class TcgResetCommand
{
	private final Client client;
	private final ClientThread clientThread;
	private final ChatMessageManager chatMessageManager;
	private final OsrsTcgConfig config;
	private final CloudSessionCoordinator cloudSessionCoordinator;
	private final SidebarRefresh sidebarRefresh;
	private final ConfigManager configManager;

	/** Stores the collaborators used to clear config, reconnect the cloud session, and refresh the UI. */
	@Inject
	public TcgResetCommand(
		Client client,
		ClientThread clientThread,
		ChatMessageManager chatMessageManager,
		OsrsTcgConfig config,
		CloudSessionCoordinator cloudSessionCoordinator,
		SidebarRefresh sidebarRefresh,
		ConfigManager configManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.chatMessageManager = chatMessageManager;
		this.config = config;
		this.cloudSessionCoordinator = cloudSessionCoordinator;
		this.sidebarRefresh = sidebarRefresh;
		this.configManager = configManager;
	}

	/** Dispatches {@code ::tcg-reset} to {@link #handleResetConfigCommand()}; ignores every other command. */
	public void onCommandExecuted(CommandExecuted event)
	{
		if (event == null || event.getCommand() == null)
		{
			return;
		}
		if (!"tcg-reset".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		handleResetConfigCommand();
	}

	/**
	 * Unsets every {@code osrstcg.*} config key (global and RS-profile-scoped), restores config defaults,
	 * reconnects the cloud session if logged in, refreshes the sidebar on the EDT, and chats a summary.
	 */
	private void handleResetConfigCommand()
	{
		final String group = "osrstcg";
		int cleared = 0;

		for (String wholeKey : configManager.getConfigurationKeys(group + "."))
		{
			if (wholeKey == null || wholeKey.length() <= group.length() + 1)
			{
				continue;
			}
			String key = wholeKey.substring(group.length() + 1);
			configManager.unsetConfiguration(group, key);
			cleared++;
		}

		String rsProfile = configManager.getRSProfileKey();
		if (rsProfile != null)
		{
			for (String key : configManager.getRSProfileConfigurationKeys(group, rsProfile, ""))
			{
				if (key == null || key.isEmpty())
				{
					continue;
				}
				configManager.unsetRSProfileConfiguration(group, key);
				cleared++;
			}
		}

		configManager.setDefaultConfiguration(config, true);
		cloudSessionCoordinator.disconnect();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			cloudSessionCoordinator.connect();
		}
		SwingUtilities.invokeLater(sidebarRefresh::refresh);
		if (client != null)
		{
			TcgPluginGameMessages.queueOnClientThread(clientThread, chatMessageManager,
				"Cleared " + cleared + " config key(s) and restored defaults.");
		}
	}
}

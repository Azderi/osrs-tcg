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
 * {@code ::tcg-reset} — clears plugin config keys and restores defaults.
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
	 * Unsets every {@code osrstcg} profile + RSProfile config key (tokens, {@code cloudMigrated},
	 * settings, legacy state blobs), restores config defaults, and reconnects with a clean consent gate.
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
		queueGameMessage("[OSRS TCG] Cleared " + cleared + " config key(s) and restored defaults.");
	}

	private void queueGameMessage(String message)
	{
		if (client == null || clientThread == null || message == null || message.isEmpty())
		{
			return;
		}
		clientThread.invokeLater(() ->
			TcgPluginGameMessages.queueGameMessage(chatMessageManager, message));
	}
}

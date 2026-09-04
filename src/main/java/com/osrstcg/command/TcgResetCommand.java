package com.osrstcg.command;

import com.osrstcg.cloud.session.CloudSessionCoordinator;
import com.osrstcg.cloud.session.CloudTokenStore;
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
 * Implements the {@code ::tcg-reset} chat command: clears cloud consent and the current account's
 * on-disk profile folder, then reconnects the cloud session. Plugin settings are left alone.
 */
@Singleton
public class TcgResetCommand
{
	private static final String GROUP = "osrstcg";
	private static final String MIGRATED = "cloudMigrated";

	private final Client client;
	private final ClientThread clientThread;
	private final ChatMessageManager chatMessageManager;
	private final CloudSessionCoordinator cloudSessionCoordinator;
	private final SidebarRefresh sidebarRefresh;
	private final ConfigManager configManager;
	private final CloudTokenStore tokenStore;

	/** Stores the collaborators used to clear consent, wipe account data, reconnect, and refresh the UI. */
	@Inject
	public TcgResetCommand(
		Client client,
		ClientThread clientThread,
		ChatMessageManager chatMessageManager,
		CloudSessionCoordinator cloudSessionCoordinator,
		SidebarRefresh sidebarRefresh,
		ConfigManager configManager,
		CloudTokenStore tokenStore)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.chatMessageManager = chatMessageManager;
		this.cloudSessionCoordinator = cloudSessionCoordinator;
		this.sidebarRefresh = sidebarRefresh;
		this.configManager = configManager;
		this.tokenStore = tokenStore;
	}

	/** Dispatches {@code ::tcg-reset} to {@link #handleResetCommand()}; ignores every other command. */
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
		handleResetCommand();
	}

	/**
	 * Clears cloud consent, wipes the logged-in account's profile folder, reconnects if logged in,
	 * and chats a summary. Does not change plugin settings.
	 */
	private void handleResetCommand()
	{
		configManager.unsetRSProfileConfiguration(GROUP, MIGRATED);
		tokenStore.wipeAccountProfileDir();

		cloudSessionCoordinator.disconnect();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			cloudSessionCoordinator.connect();
		}
		SwingUtilities.invokeLater(sidebarRefresh::refresh);
		if (client != null)
		{
			TcgPluginGameMessages.queueOnClientThread(clientThread, chatMessageManager,
				"Cleared cloud consent and wiped account profile folder.");
		}
	}
}

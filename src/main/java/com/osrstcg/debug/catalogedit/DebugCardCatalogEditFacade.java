package com.osrstcg.debug.catalogedit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.ui.collectionalbum.AlbumSlot;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * DEBUG_CARD_EDIT entry point for album interactions. Delete this class with the rest of
 * {@code com.osrstcg.debug.catalogedit} when removing the feature.
 */
@Singleton
@Slf4j
public final class DebugCardCatalogEditFacade
{
	private final DebugCardEditGate gate;
	private final DebugCardJsonFileStore fileStore;
	private final DebugCardJsonPaths paths;
	private final DebugCatalogReloader reloader;

	@Inject
	public DebugCardCatalogEditFacade(
		DebugCardEditGate gate,
		DebugCardJsonFileStore fileStore,
		DebugCardJsonPaths paths,
		DebugCatalogReloader reloader)
	{
		this.gate = gate;
		this.fileStore = fileStore;
		this.paths = paths;
		this.reloader = reloader;
	}

	/**
	 * @return {@code true} if a debug context menu was shown (caller should not treat click as selection).
	 */
	public boolean tryShowAlbumCardContextMenu(MouseEvent e, Component parent, AlbumSlot slot)
	{
		if (!gate.isEnabled() || e == null || slot == null)
		{
			return false;
		}
		CardDefinition card = slot.card();
		if (card == null || card.getName() == null || card.getName().trim().isEmpty())
		{
			return false;
		}

		Optional<Path> jsonPath = paths.resolveWorkspaceCardJson();
		String missingPathTip =
			"Workspace Card.json not found (run from project root or set -Dosrstcg.cardJsonPath=…)";

		JPopupMenu menu = new JPopupMenu();
		JMenuItem editItem = new JMenuItem("Edit card definition (DEBUG)…");
		JMenuItem deleteItem = new JMenuItem("Delete card");
		if (jsonPath.isEmpty())
		{
			editItem.setEnabled(false);
			editItem.setToolTipText(missingPathTip);
			deleteItem.setEnabled(false);
			deleteItem.setToolTipText(missingPathTip);
		}
		else
		{
			Path path = jsonPath.get();
			editItem.addActionListener(ev -> openEditor(parent, card, path));
			deleteItem.addActionListener(ev -> confirmAndDeleteCard(parent, card.getName(), path));
		}
		menu.add(editItem);
		menu.add(deleteItem);
		menu.show(e.getComponent(), e.getX(), e.getY());
		return true;
	}

	private void openEditor(Component parent, CardDefinition card, Path jsonPath)
	{
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				JsonArray array = fileStore.readArray(jsonPath);
				Optional<JsonObject> json = fileStore.findByName(array, card.getName());
				if (json.isEmpty())
				{
					JOptionPane.showMessageDialog(parent,
						"Card \"" + card.getName() + "\" was not found in " + jsonPath,
						"DEBUG card editor",
						JOptionPane.ERROR_MESSAGE);
					return;
				}
				DebugCardEditorDialog dialog = new DebugCardEditorDialog(
					parent, fileStore, reloader, jsonPath, card, json.get());
				dialog.setVisible(true);
			}
			catch (Exception ex)
			{
				log.warn("DEBUG_CARD_EDIT: failed opening editor for {}", card.getName(), ex);
				JOptionPane.showMessageDialog(parent,
					DebugCardJsonFileStore.readErrorMessage(ex),
					"DEBUG card editor",
					JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	private void confirmAndDeleteCard(Component parent, String cardName, Path jsonPath)
	{
		int confirm = JOptionPane.showConfirmDialog(
			parent,
			"Remove \"" + cardName + "\" from Card.json?\nOwned collection copies are not removed.",
			"Delete card",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		try
		{
			fileStore.deleteCard(jsonPath, cardName);
			reloader.reloadEntireCatalog();
		}
		catch (Exception ex)
		{
			log.warn("DEBUG_CARD_EDIT: failed deleting {}", cardName, ex);
			JOptionPane.showMessageDialog(parent,
				DebugCardJsonFileStore.readErrorMessage(ex),
				"Delete card",
				JOptionPane.ERROR_MESSAGE);
		}
	}

}

package com.osrstcg.ui.save;

import com.osrstcg.persist.TcgSaveMetadataEntry;
import com.osrstcg.state.TcgStateService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * Opens the cloud migrate upload save picker (legacy {@code backups/} structure only).
 */
@Singleton
public final class MigrateSavePicker
{
	private final TcgStateService stateService;
	private volatile MigrateSavePickerDialog dialog;

	@Inject
	public MigrateSavePicker(TcgStateService stateService)
	{
		this.stateService = stateService;
	}

	/**
	 * Opens the migrate upload picker on the EDT when disk save files exist.
	 * {@code onUploadAccepted} receives the selected entry.
	 * Cancel closes without calling {@code onUploadAccepted}.
	 */
	public void showMigratePicker(Consumer<TcgSaveMetadataEntry> onUploadAccepted, Runnable onNoSaves)
	{
		Runnable open = () ->
		{
			if (!stateService.hasDiskSaves())
			{
				if (onNoSaves != null)
				{
					onNoSaves.run();
				}
				return;
			}

			if (dialog != null && dialog.isDisplayable())
			{
				dialog.toFront();
				return;
			}

			dialog = new MigrateSavePickerDialog(stateService, entry ->
			{
				dialog = null;
				if (onUploadAccepted != null)
				{
					onUploadAccepted.accept(entry);
				}
			});
			dialog.addWindowListener(new java.awt.event.WindowAdapter()
			{
				@Override
				public void windowClosed(java.awt.event.WindowEvent e)
				{
					dialog = null;
				}
			});
			dialog.setVisible(true);
		};

		if (SwingUtilities.isEventDispatchThread())
		{
			open.run();
		}
		else
		{
			SwingUtilities.invokeLater(open);
		}
	}

	public void dispose()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (dialog != null)
			{
				dialog.dispose();
				dialog = null;
			}
		});
	}
}

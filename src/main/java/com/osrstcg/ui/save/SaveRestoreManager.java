package com.osrstcg.ui.save;

import com.osrstcg.state.TcgStateService;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * Opens the migrate save picker (current profile only).
 */
@Singleton
public final class SaveRestoreManager
{
	private final TcgStateService stateService;
	private volatile SaveRestoreDialog dialog;

	@Inject
	public SaveRestoreManager(TcgStateService stateService)
	{
		this.stateService = stateService;
	}

	/**
	 * Opens the migrate upload picker on the EDT when disk save files exist.
	 * {@code onUploadAccepted} receives the selected file name.
	 * Cancel closes without calling {@code onUploadAccepted}.
	 * {@code onNoSaves} is unused when callers already gated on {@link TcgStateService#hasDiskSaves()};
	 * kept for callers that want a fallback (may be {@code null}).
	 */
	public void showMigratePicker(Consumer<String> onUploadAccepted, Runnable onNoSaves)
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

			dialog = new SaveRestoreDialog(stateService, fileName ->
			{
				dialog = null;
				if (onUploadAccepted != null)
				{
					onUploadAccepted.accept(fileName);
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

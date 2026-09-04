package com.osrstcg.overlay;

import com.osrstcg.pack.PackRevealService;
import com.osrstcg.ui.SidebarRefresh;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;
/**
 * RuneLite mouse/keyboard listener that intercepts and consumes game input while a pack reveal is
 * active, routing clicks/keys to the reveal service and overlay instead. Callbacks run on RuneLite's
 * input handling thread, not the client thread.
 */
@Singleton
public class PackRevealInputListener implements MouseListener, KeyListener, MouseWheelListener
{
	private final PackRevealService revealService;
	private final PackRevealOverlay overlay;
	private final SidebarRefresh sidebarRefresh;
	private final ChatMessageManager chatMessageManager;
/** Wires the collaborators used to detect an active reveal, forward input to it, and refresh the sidebar after close. */
	@Inject
	public PackRevealInputListener(
		PackRevealService revealService,
		PackRevealOverlay overlay,
		SidebarRefresh sidebarRefresh,
		ChatMessageManager chatMessageManager)
	{
		this.revealService = revealService;
		this.overlay = overlay;
		this.sidebarRefresh = sidebarRefresh;
		this.chatMessageManager = chatMessageManager;
	}
/**
	 * Aborts the active reveal (no-op if none), optionally chat-announcing {@code reasonMessage} first,
	 * then refreshes the sidebar.
	 */
	private void forceCloseActiveReveal(String reasonMessage)
	{
		if (!revealService.isActive())
		{
			return;
		}
		if (reasonMessage != null && !reasonMessage.isBlank())
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, reasonMessage);
		}
		revealService.abortActiveReveal();
		sidebarRefresh.refreshAfterPackRevealClose();
	}
/** Consumes game input only while a pack reveal overlay is active. */
	private boolean revealBlocksGameInput()
	{
		return revealService.isActive();
	}
/** Updates the overlay's hover point from {@code e} while a reveal is active, else clears it. */
	private void syncRevealHoverCanvasFromEvent(java.awt.event.MouseEvent e)
	{
		if (e == null)
		{
			return;
		}
		if (!revealBlocksGameInput())
		{
			overlay.setRevealHoverCanvasPoint(null);
			return;
		}
		overlay.setRevealHoverCanvasPoint(e.getPoint());
	}
/** Consumes clicks while a reveal is active; otherwise passes the event through unconsumed. */
	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		if (!revealBlocksGameInput() || mouseEvent == null)
		{
			return mouseEvent;
		}
		mouseEvent.consume();
		return mouseEvent;
	}
/**
	 * Syncs the hover point, then while a reveal is active: right-click pins the card info tip; left-click
	 * hits the pinned tip, the close button, or forwards to {@link PackRevealService#handleClick}, refreshing
	 * the sidebar if that ends the reveal. Consumes the event whenever a reveal is active.
	 */
	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		if (mouseEvent == null)
		{
			return mouseEvent;
		}
		syncRevealHoverCanvasFromEvent(mouseEvent);
		if (!revealBlocksGameInput())
		{
			return mouseEvent;
		}

		if (mouseEvent.getButton() == MouseEvent.BUTTON3)
		{
			overlay.pinCardInfoTipAt(mouseEvent.getPoint());
			mouseEvent.consume();
			return mouseEvent;
		}

		if (mouseEvent.getButton() == MouseEvent.BUTTON1)
		{
			if (overlay.isCardInfoTipPinned())
			{
				if (overlay.handlePinnedTipClick(mouseEvent.getPoint()))
				{
					mouseEvent.consume();
					return mouseEvent;
				}
			}
			if (overlay.handleCloseButtonClick(mouseEvent.getPoint()))
			{
				forceCloseActiveReveal(
					"Pack reveal closed - your cards are in your collection.");
				mouseEvent.consume();
				return mouseEvent;
			}
			revealService.handleClick(mouseEvent.getPoint(), overlay.currentPackBounds(), overlay.currentCardBounds());
			if (!revealService.isActive())
			{
				sidebarRefresh.refreshAfterPackRevealClose();
			}
		}
		mouseEvent.consume();
		return mouseEvent;
	}
/** Consumes the release while a reveal is active; otherwise passes it through unconsumed. */
	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		if (!revealBlocksGameInput() || mouseEvent == null)
		{
			return mouseEvent;
		}
		mouseEvent.consume();
		return mouseEvent;
	}
/** Consumes the enter event while a reveal is active; otherwise passes it through unconsumed. */
	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		if (!revealBlocksGameInput() || mouseEvent == null)
		{
			return mouseEvent;
		}
		mouseEvent.consume();
		return mouseEvent;
	}
/** Clears the overlay's hover point, then consumes the exit event while a reveal is active. */
	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		overlay.setRevealHoverCanvasPoint(null);
		if (!revealBlocksGameInput() || mouseEvent == null)
		{
			return mouseEvent;
		}
		mouseEvent.consume();
		return mouseEvent;
	}
/** Syncs the hover point, then consumes the drag event while a reveal is active. */
	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		if (mouseEvent == null)
		{
			return mouseEvent;
		}
		syncRevealHoverCanvasFromEvent(mouseEvent);
		if (!revealBlocksGameInput())
		{
			return mouseEvent;
		}
		mouseEvent.consume();
		return mouseEvent;
	}
/** Syncs the hover point, then consumes the move event while a reveal is active. */
	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		if (mouseEvent == null)
		{
			return mouseEvent;
		}
		syncRevealHoverCanvasFromEvent(mouseEvent);
		if (!revealBlocksGameInput())
		{
			return mouseEvent;
		}
		mouseEvent.consume();
		return mouseEvent;
	}
/** Syncs the hover point, then nudges the reveal's zoom level and consumes the event while a reveal is active. */
	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		if (event == null)
		{
			return event;
		}
		syncRevealHoverCanvasFromEvent(event);
		if (!revealBlocksGameInput())
		{
			return event;
		}
		overlay.nudgeSessionPackZoom(event.getWheelRotation());
		event.consume();
		return event;
	}
/** Consumes typed characters while a reveal is active; otherwise leaves the event alone. */
	@Override
	public void keyTyped(KeyEvent e)
	{
		if (!revealBlocksGameInput() || e == null)
		{
			return;
		}
		e.consume();
	}
/**
	 * While a reveal is active: Escape force-closes the reveal, Space advances it (refreshing the sidebar
	 * if that ends the reveal); all other keys are simply consumed.
	 */
	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!revealBlocksGameInput() || e == null)
		{
			return;
		}
		if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
		{
			forceCloseActiveReveal(
				"Pack reveal closed - your cards are in your collection.");
			e.consume();
			return;
		}
		if (e.getKeyCode() == KeyEvent.VK_SPACE)
		{
			revealService.advanceFromKeyboard();
			if (!revealService.isActive())
			{
				sidebarRefresh.refreshAfterPackRevealClose();
			}
		}
		e.consume();
	}
/** Consumes the key release while a reveal is active; otherwise leaves the event alone. */
	@Override
	public void keyReleased(KeyEvent e)
	{
		if (!revealBlocksGameInput() || e == null)
		{
			return;
		}
		e.consume();
	}
}

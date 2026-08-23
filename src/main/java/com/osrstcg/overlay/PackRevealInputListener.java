package com.osrstcg.overlay;

import com.osrstcg.pack.PackRevealService;
import com.osrstcg.pack.PackSafeModeService;
import com.osrstcg.ui.SidebarRefresh;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;

@Singleton
public class PackRevealInputListener implements MouseListener, KeyListener, MouseWheelListener
{
	private final PackRevealService revealService;
	private final PackRevealOverlay overlay;
	private final SidebarRefresh sidebarRefresh;
	private final PackSafeModeService packSafeModeService;

	@Inject
	public PackRevealInputListener(
		PackRevealService revealService,
		PackRevealOverlay overlay,
		SidebarRefresh sidebarRefresh,
		PackSafeModeService packSafeModeService)
	{
		this.revealService = revealService;
		this.overlay = overlay;
		this.sidebarRefresh = sidebarRefresh;
		this.packSafeModeService = packSafeModeService;
	}

	private boolean revealBlocksGameInput()
	{
		return revealService.isActive();
	}

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
			// Freeze tip + context menu; do not advance the reveal.
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
				packSafeModeService.forceCloseActiveReveal(
					"Pack reveal closed - your cards are in your collection.");
				mouseEvent.consume();
				return mouseEvent;
			}
			revealService.handleClick(mouseEvent.getPoint(), overlay.currentPackBounds(), overlay.currentCardBounds());
			// Full sidebar refresh only when the reveal session ends (not on every card flip).
			if (!revealService.isActive())
			{
				sidebarRefresh.refreshAfterPackRevealClose();
			}
		}
		mouseEvent.consume();
		return mouseEvent;
	}

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

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (!revealBlocksGameInput() || e == null)
		{
			return;
		}
		e.consume();
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!revealBlocksGameInput() || e == null)
		{
			return;
		}
		if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
		{
			packSafeModeService.forceCloseActiveReveal(
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

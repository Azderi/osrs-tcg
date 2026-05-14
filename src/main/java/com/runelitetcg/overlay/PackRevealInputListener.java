package com.runelitetcg.overlay;

import com.runelitetcg.service.PackRevealService;
import com.runelitetcg.ui.TcgPanel;
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
	private final TcgPanel tcgPanel;

	@Inject
	public PackRevealInputListener(PackRevealService revealService, PackRevealOverlay overlay, TcgPanel tcgPanel)
	{
		this.revealService = revealService;
		this.overlay = overlay;
		this.tcgPanel = tcgPanel;
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
			if (revealService.revealAllCards())
			{
				tcgPanel.refreshAfterPackRevealClose();
			}
			mouseEvent.consume();
			return mouseEvent;
		}

		if (mouseEvent.getButton() == MouseEvent.BUTTON1)
		{
			revealService.handleClick(mouseEvent.getPoint(), overlay.currentPackBounds(), overlay.currentCardBounds());
			tcgPanel.refreshAfterPackRevealClose();
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
			revealService.skip();
			tcgPanel.refreshAfterPackRevealClose();
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

package com.osrstcg;

import com.google.gson.Gson;
import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.service.RarityMath;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.CardEffectRenderer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import okhttp3.OkHttpClient;

public final class CardEffectPreview
{
	private CardEffectPreview()
	{
	}

	public static void main(String[] args)
	{
		SwingUtilities.invokeLater(() ->
		{
			CardDatabase database = new CardDatabase(new Gson());
			database.load();
			List<CardDefinition> cards = database.getCards().stream()
				.filter(card -> card != null && card.getName() != null && !card.getName().trim().isEmpty())
				.filter(card -> card.getImageUrl() != null && !card.getImageUrl().trim().isEmpty())
				.collect(Collectors.toCollection(ArrayList::new));
			if (cards.isEmpty())
			{
				throw new IllegalStateException("No cards with artwork loaded from Card.json");
			}

			Map<String, RarityMath.Tier> tierByName = RarityMath.displayTierByCardName(cards);
			WikiImageCacheService imageCacheService = new WikiImageCacheService(new OkHttpClient());
			PreviewPanel panel = new PreviewPanel(cards, tierByName, imageCacheService);
			JButton randomize = new JButton("Randomize");
			randomize.addActionListener(event -> panel.randomizeCards());
			JPanel controls = buildControls(panel, randomize);

			JPanel root = new JPanel(new java.awt.BorderLayout());
			root.setBackground(new Color(0x11151D));
			root.add(panel, java.awt.BorderLayout.CENTER);
			root.add(controls, java.awt.BorderLayout.SOUTH);

			JFrame frame = new JFrame("OSRS TCG Card Effect Preview");
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			frame.setContentPane(root);
			frame.pack();
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);

			panel.requestFocusInWindow();
		});
	}

	private static JPanel buildControls(PreviewPanel panel, JButton randomize)
	{
		JPanel controls = new JPanel(new GridLayout(0, 1, 4, 4));
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
		actions.add(randomize);
		controls.add(actions);
		controls.add(buildCardControl("Normal", panel.normalControl, panel));
		controls.add(buildCardControl("Holographic", panel.holoControl, panel));
		return controls;
	}

	private static JPanel buildCardControl(String label, ViewControl control, PreviewPanel panel)
	{
		JCheckBox locked = new JCheckBox("Lock " + label);
		control.lockedBox = locked;
		locked.addActionListener(event ->
		{
			control.locked = locked.isSelected();
			panel.repaint();
			panel.requestFocusInWindow();
		});

		JSlider x = new JSlider(-100, 100, 0);
		control.xSlider = x;
		x.setMajorTickSpacing(50);
		x.setPaintTicks(true);
		x.addChangeListener(event ->
		{
			control.x = x.getValue();
			panel.repaint();
		});

		JSlider y = new JSlider(-100, 100, 0);
		control.ySlider = y;
		y.setMajorTickSpacing(50);
		y.setPaintTicks(true);
		y.addChangeListener(event ->
		{
			control.y = y.getValue();
			panel.repaint();
		});

		JPanel row = new JPanel(new GridLayout(1, 5, 8, 0));
		row.add(locked);
		row.add(new JLabel("X"));
		row.add(x);
		row.add(new JLabel("Y"));
		row.add(y);
		return row;
	}

	private static final class PreviewPanel extends JPanel
	{
		private static final int CARD_W = 260;
		private static final int CARD_H = 376;
		private static final Color BACKGROUND_TOP = new Color(0x151A24);
		private static final Color BACKGROUND_BOTTOM = new Color(0x090C12);
		private static final CardEffectRenderer.Options EFFECT_OPTIONS =
			new CardEffectRenderer.Options(true, true, true, true);

		private final Random random = new Random();
		private final List<CardDefinition> cards;
		private final List<CardDefinition> holoCards;
		private final Map<String, RarityMath.Tier> tierByName;
		private final WikiImageCacheService imageCacheService;
		private final ViewControl normalControl = new ViewControl();
		private final ViewControl holoControl = new ViewControl();
		private CardDefinition normalCard;
		private CardDefinition holoCard;
		private Point mousePoint = new Point(-1000, -1000);

		private PreviewPanel(List<CardDefinition> cards, Map<String, RarityMath.Tier> tierByName,
			WikiImageCacheService imageCacheService)
		{
			this.cards = cards;
			this.tierByName = tierByName;
			this.holoCards = cards.stream()
				.filter(card -> isLegendaryOrBetter(tierByName.getOrDefault(card.getName(), RarityMath.Tier.COMMON)))
				.collect(Collectors.toCollection(ArrayList::new));
			if (holoCards.isEmpty())
			{
				throw new IllegalStateException("No Legendary-or-better cards with artwork loaded from Card.json");
			}
			this.imageCacheService = imageCacheService;
			setPreferredSize(new Dimension(900, 560));
			setFocusable(true);
			randomizeCards();

			MouseAdapter mouse = new MouseAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent event)
				{
					mousePoint = event.getPoint();
				}

				@Override
				public void mouseDragged(MouseEvent event)
				{
					mousePoint = event.getPoint();
				}

				@Override
				public void mouseExited(MouseEvent event)
				{
					mousePoint = new Point(-1000, -1000);
				}

				@Override
				public void mouseClicked(MouseEvent event)
				{
					capturePointerPosition(event.getPoint());
				}
			};
			addMouseMotionListener(mouse);
			addMouseListener(mouse);
			addKeyListener(new KeyAdapter()
			{
				@Override
				public void keyPressed(KeyEvent event)
				{
					if (event.getKeyCode() == KeyEvent.VK_SPACE || event.getKeyCode() == KeyEvent.VK_R)
					{
						randomizeCards();
					}
				}
			});

			new Timer(16, event -> repaint()).start();
		}

		private void randomizeCards()
		{
			normalCard = cards.get(random.nextInt(cards.size()));
			holoCard = holoCards.get(random.nextInt(holoCards.size()));
			imageCacheService.preload(List.of(normalCard.getImageUrl(), holoCard.getImageUrl()));
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				enableQuality(g);
				g.setPaint(new GradientPaint(0, 0, BACKGROUND_TOP, 0, getHeight(), BACKGROUND_BOTTOM));
				g.fillRect(0, 0, getWidth(), getHeight());

				int gap = 90;
				int totalW = CARD_W * 2 + gap;
				int left = Math.max(30, (getWidth() - totalW) / 2);
				int top = Math.max(58, (getHeight() - CARD_H) / 2 + 18);
				Rectangle normalBounds = new Rectangle(left, top, CARD_W, CARD_H);
				Rectangle holoBounds = new Rectangle(left + CARD_W + gap, top, CARD_W, CARD_H);

				drawTitle(g, "Normal", normalBounds, normalCard);
				drawTitle(g, "Holographic", holoBounds, holoCard);
				drawHint(g);

				BufferedImage normalArt = imageCacheService.getCached(normalCard.getImageUrl());
				BufferedImage holoArt = imageCacheService.getCached(holoCard.getImageUrl());
				Point normalPointer = pointerFor(normalBounds, normalControl);
				Point holoPointer = pointerFor(holoBounds, holoControl);
				CardEffectRenderer.drawCardFace(g, normalBounds, normalCard, false, rarityColor(normalCard), normalArt,
					0L, false, normalPointer.x, normalPointer.y, effectStrength(normalBounds, normalControl),
					false, EFFECT_OPTIONS);
				CardEffectRenderer.drawCardFace(g, holoBounds, holoCard, true, rarityColor(holoCard), holoArt,
					0L, true, holoPointer.x, holoPointer.y, effectStrength(holoBounds, holoControl),
					true, EFFECT_OPTIONS);
			}
			finally
			{
				g.dispose();
			}
		}

		private Color rarityColor(CardDefinition card)
		{
			if (card == null)
			{
				return Color.WHITE;
			}
			RarityMath.Tier tier = tierByName.getOrDefault(card.getName(), RarityMath.Tier.COMMON);
			return tier.getColor();
		}

		private static boolean isLegendaryOrBetter(RarityMath.Tier tier)
		{
			return tier == RarityMath.Tier.LEGENDARY
				|| tier == RarityMath.Tier.MYTHIC
				|| tier == RarityMath.Tier.GODLY;
		}

		private Point pointerFor(Rectangle bounds, ViewControl control)
		{
			if (!control.locked)
			{
				return mousePoint;
			}
			int x = (int) Math.round(bounds.getCenterX() + bounds.width * control.x / 200.0d);
			int y = (int) Math.round(bounds.getCenterY() + bounds.height * control.y / 200.0d);
			return new Point(x, y);
		}

		private double effectStrength(Rectangle bounds, ViewControl control)
		{
			if (control.locked)
			{
				return 1.0d;
			}
			return bounds.contains(mousePoint) ? 1.0d : 0.0d;
		}

		private void capturePointerPosition(Point point)
		{
			Rectangle normalBounds = cardBounds(false);
			Rectangle holoBounds = cardBounds(true);
			if (normalBounds.contains(point))
			{
				normalControl.capture(point, normalBounds);
			}
			else if (holoBounds.contains(point))
			{
				holoControl.capture(point, holoBounds);
			}
			repaint();
		}

		private Rectangle cardBounds(boolean holographic)
		{
			int gap = 90;
			int totalW = CARD_W * 2 + gap;
			int left = Math.max(30, (getWidth() - totalW) / 2);
			int top = Math.max(58, (getHeight() - CARD_H) / 2 + 18);
			return holographic
				? new Rectangle(left + CARD_W + gap, top, CARD_W, CARD_H)
				: new Rectangle(left, top, CARD_W, CARD_H);
		}
	}

	private static final class ViewControl
	{
		private boolean locked;
		private int x;
		private int y;
		private JCheckBox lockedBox;
		private JSlider xSlider;
		private JSlider ySlider;

		private void capture(Point point, Rectangle bounds)
		{
			locked = true;
			x = (int) Math.round((point.x - bounds.getCenterX()) / Math.max(1.0d, bounds.width / 2.0d) * 100.0d);
			y = (int) Math.round((point.y - bounds.getCenterY()) / Math.max(1.0d, bounds.height / 2.0d) * 100.0d);
			x = Math.max(-100, Math.min(100, x));
			y = Math.max(-100, Math.min(100, y));
			if (lockedBox != null)
			{
				lockedBox.setSelected(true);
			}
			if (xSlider != null)
			{
				xSlider.setValue(x);
			}
			if (ySlider != null)
			{
				ySlider.setValue(y);
			}
		}
	}

	private static void drawTitle(Graphics2D g, String title, Rectangle bounds, CardDefinition card)
	{
		g.setColor(new Color(0xEEF3FF));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
		g.drawString(title, bounds.x, bounds.y - 28);

		g.setColor(new Color(0x9DA8BA));
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		String name = card == null ? "" : card.getName();
		FontMetrics metrics = g.getFontMetrics();
		if (metrics.stringWidth(name) > bounds.width)
		{
			while (name.length() > 3 && metrics.stringWidth(name + "...") > bounds.width)
			{
				name = name.substring(0, name.length() - 1);
			}
			name += "...";
		}
		g.drawString(name, bounds.x, bounds.y - 9);
	}

	private static void drawHint(Graphics2D g)
	{
		g.setColor(new Color(0x758196));
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		g.drawString("Move mouse over a card to tilt. Click a card to lock that position. Space or R randomizes.", 24, 28);
	}

	private static void enableQuality(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
	}
}

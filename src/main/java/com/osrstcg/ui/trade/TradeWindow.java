package com.osrstcg.ui.trade;

import com.osrstcg.data.CardDatabase;
import com.osrstcg.data.CardDefinition;
import com.osrstcg.service.CardPartyTradeService;
import com.osrstcg.service.CardPartyTradeService.TradeOfferView;
import com.osrstcg.service.CardPartyTradeService.TradeSessionView;
import com.osrstcg.service.WikiImageCacheService;
import com.osrstcg.ui.SharedCardRenderer;
import com.osrstcg.ui.collectionalbum.AlbumInstanceTooltip;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * Two-sided party trade window: local offers on the left, partner on the right.
 * Default viewport shows a 3×2 grid; additional cards scroll vertically.
 */
public final class TradeWindow extends JFrame
{
	private static final BufferedImage WINDOW_ICON =
		ImageUtil.loadImageResource(TradeWindow.class, "/icon.png");

	private static final int COLS = 3;
	private static final int GAP = 8;
	/** Sized so the default scroll viewport fits a 3×2 grid. */
	private static final double CARD_SCALE = 0.58d;
	private static final int VISIBLE_ROWS = 2;

	private final CardDatabase cardDatabase;
	private final WikiImageCacheService imageCacheService;
	private final CardPartyTradeService tradeService;

	private final JLabel localTitle = new JLabel("You", SwingConstants.CENTER);
	private final JLabel remoteTitle = new JLabel("Partner", SwingConstants.CENTER);
	private final OfferPanel localPanel;
	private final OfferPanel remotePanel;
	private JScrollPane localScroll;
	private JScrollPane remoteScroll;
	private final JLabel statusLabel = new JLabel(" ");
	private final JButton acceptBtn = new JButton("Accept");
	private final JButton cancelBtn = new JButton("Cancel");
	private final Timer imagePollTimer;
	private final Timer foilAnimTimer;
	private boolean suppressCloseCancel;

	TradeWindow(
		CardDatabase cardDatabase,
		WikiImageCacheService imageCacheService,
		CardPartyTradeService tradeService)
	{
		super("OSRS TCG — Trade");
		if (WINDOW_ICON != null)
		{
			setIconImage(WINDOW_ICON);
		}
		this.cardDatabase = cardDatabase;
		this.imageCacheService = imageCacheService;
		this.tradeService = tradeService;

		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		setResizable(false);
		setLayout(new BorderLayout(8, 8));
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);

		localPanel = new OfferPanel(true);
		remotePanel = new OfferPanel(false);

		JPanel center = new JPanel(new java.awt.GridLayout(1, 2, 12, 0));
		center.setOpaque(false);
		center.setBorder(new EmptyBorder(6, 10, 2, 10));
		center.add(wrapSide(localTitle, localPanel, true));
		center.add(wrapSide(remoteTitle, remotePanel, false));
		add(center, BorderLayout.CENTER);

		JPanel south = new JPanel(new BorderLayout(8, 4));
		south.setOpaque(false);
		south.setBorder(new EmptyBorder(0, 10, 8, 10));
		statusLabel.setForeground(Color.WHITE);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		south.add(statusLabel, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setOpaque(false);
		styleButton(acceptBtn);
		styleButton(cancelBtn);
		acceptBtn.addActionListener(e -> onAcceptClicked());
		cancelBtn.addActionListener(e -> onCancelClicked());
		buttons.add(acceptBtn);
		buttons.add(cancelBtn);
		south.add(buttons, BorderLayout.EAST);
		add(south, BorderLayout.SOUTH);

		pack();
		setMinimumSize(getSize());
		setMaximumSize(getSize());

		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				// Closing the window cancels the trade for both players.
				setVisible(false);
			}
		});

		imagePollTimer = new Timer(250, e ->
		{
			if (isShowing())
			{
				localPanel.repaint();
				remotePanel.repaint();
			}
		});
		foilAnimTimer = new Timer(SharedCardRenderer.FOIL_SPARKLE_FRAME_MS, e ->
		{
			if (isShowing())
			{
				localPanel.repaint();
				remotePanel.repaint();
			}
		});
	}

	void prepareToShow()
	{
		refreshFromService();
		if (!imagePollTimer.isRunning())
		{
			imagePollTimer.start();
		}
		if (!foilAnimTimer.isRunning())
		{
			foilAnimTimer.start();
		}
	}

	@Override
	public void setVisible(boolean visible)
	{
		if (!visible && isShowing() && !suppressCloseCancel && tradeService.isTradeActive())
		{
			tradeService.cancelActiveTrade();
			// cancelActiveTrade hides via hideWithoutCancel; avoid a second hide path
			if (!isShowing())
			{
				return;
			}
		}
		super.setVisible(visible);
	}

	void refreshFromService()
	{
		TradeSessionView view = tradeService.getSessionView();
		if (view == null)
		{
			localPanel.setOffers(List.of());
			remotePanel.setOffers(List.of());
			updateScrollBarPolicies(0, 0);
			statusLabel.setText("No active trade.");
			acceptBtn.setEnabled(false);
			acceptBtn.setText("Accept");
			return;
		}

		localTitle.setText(view.getLocalDisplayName());
		remoteTitle.setText(view.getPartnerDisplayName());

		localPanel.setOffers(view.getLocalOffers());
		remotePanel.setOffers(view.getRemoteOffers());
		updateScrollBarPolicies(view.getLocalOffers().size(), view.getRemoteOffers().size());

		String status;
		if (view.isLocalReady() && view.isRemoteReady())
		{
			status = "Both accepted — completing trade…";
		}
		else if (view.isLocalReady())
		{
			status = "Waiting for " + view.getPartnerDisplayName() + " to accept…";
		}
		else if (view.isRemoteReady())
		{
			status = view.getPartnerDisplayName() + " has accepted. Click Accept to complete.";
		}
		else
		{
			status = "Offer cards from the album, then click Accept.";
		}
		statusLabel.setText(status);
		acceptBtn.setEnabled(!view.isLocalReady());
		acceptBtn.setText(view.isLocalReady() ? "Accepted" : "Accept");
		localPanel.repaint();
		remotePanel.repaint();
	}

	private JPanel wrapSide(JLabel titleLabel, OfferPanel panel, boolean localSide)
	{
		JPanel wrap = new JPanel(new BorderLayout(0, 4));
		wrap.setOpaque(false);
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		wrap.add(titleLabel, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(panel);
		scroll.setOpaque(true);
		scroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scroll.getViewport().setOpaque(true);
		scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(Math.max(16, cardHeight() / 2));
		Dimension vp = viewportSizeForVisibleGrid();
		scroll.setPreferredSize(vp);
		scroll.setMinimumSize(vp);
		scroll.setMaximumSize(vp);
		if (localSide)
		{
			localScroll = scroll;
		}
		else
		{
			remoteScroll = scroll;
		}
		wrap.add(scroll, BorderLayout.CENTER);
		return wrap;
	}

	private void updateScrollBarPolicies(int localCount, int remoteCount)
	{
		applyScrollBarPolicy(localScroll, localCount);
		applyScrollBarPolicy(remoteScroll, remoteCount);
	}

	private static void applyScrollBarPolicy(JScrollPane scroll, int offerCount)
	{
		if (scroll == null)
		{
			return;
		}
		// Hide scrollbar until more than one full 3×2 page (6 cards) is offered.
		int policy = offerCount < (COLS * VISIBLE_ROWS)
			? ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
			: ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED;
		if (scroll.getVerticalScrollBarPolicy() != policy)
		{
			scroll.setVerticalScrollBarPolicy(policy);
		}
	}

	private static int cardWidth()
	{
		return Math.max(1, (int) Math.round(SharedCardRenderer.DEFAULT_CARD_WIDTH * CARD_SCALE));
	}

	private static int cardHeight()
	{
		return Math.max(1, (int) Math.round(SharedCardRenderer.DEFAULT_CARD_HEIGHT * CARD_SCALE));
	}

	private static int verticalScrollBarWidth()
	{
		javax.swing.JScrollBar bar = new javax.swing.JScrollBar(javax.swing.JScrollBar.VERTICAL);
		return Math.max(15, bar.getPreferredSize().width);
	}

	/**
	 * Viewport sized to show {@link #COLS}×{@link #VISIBLE_ROWS} cards, plus scrollbar width so the
	 * grid does not shrink when the bar appears.
	 */
	private static Dimension viewportSizeForVisibleGrid()
	{
		int cW = cardWidth();
		int cH = cardHeight();
		int width = GAP + COLS * (cW + GAP) + verticalScrollBarWidth();
		int height = GAP + VISIBLE_ROWS * (cH + GAP);
		return new Dimension(width, height);
	}

	void disposeInternal()
	{
		suppressCloseCancel = true;
		imagePollTimer.stop();
		foilAnimTimer.stop();
		dispose();
	}

	void hideWithoutCancel()
	{
		suppressCloseCancel = true;
		setVisible(false);
		suppressCloseCancel = false;
	}

	private void onAcceptClicked()
	{
		String err = tradeService.setLocalReady(true);
		if (err != null)
		{
			statusLabel.setText(err);
		}
		refreshFromService();
	}

	private void onCancelClicked()
	{
		tradeService.cancelActiveTrade();
	}

	private static void styleButton(JButton btn)
	{
		btn.setFocusable(false);
		btn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		btn.setForeground(Color.WHITE);
	}

	private final class OfferPanel extends JPanel
	{
		private final boolean localSide;
		private List<TradeOfferView> offers = List.of();
		private List<Rectangle> cardBounds = List.of();

		OfferPanel(boolean localSide)
		{
			this.localSide = localSide;
			setOpaque(true);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(new EmptyBorder(0, 0, 0, 0));
			setToolTipText("");
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (!localSide || !javax.swing.SwingUtilities.isLeftMouseButton(e))
					{
						return;
					}
					int idx = hitIndex(e.getX(), e.getY());
					if (idx < 0 || idx >= offers.size())
					{
						return;
					}
					TradeOfferView o = offers.get(idx);
					String err = tradeService.removeOfferedCard(o.getCardInstanceId());
					if (err != null)
					{
						statusLabel.setText(err);
					}
					refreshFromService();
				}
			});
		}

		void setOffers(List<TradeOfferView> next)
		{
			this.offers = next == null ? List.of() : List.copyOf(next);
			revalidate();
			repaint();
		}

		@Override
		public String getToolTipText(MouseEvent event)
		{
			if (event == null)
			{
				return null;
			}
			int idx = hitIndex(event.getX(), event.getY());
			if (idx < 0 || idx >= offers.size())
			{
				return null;
			}
			TradeOfferView o = offers.get(idx);
			return AlbumInstanceTooltip.format(o.getPulledByUsername(), o.getPulledAtEpochMs(), false);
		}

		@Override
		public Dimension getPreferredSize()
		{
			int cW = cardWidth();
			int cH = cardHeight();
			int rows = offers.isEmpty() ? VISIBLE_ROWS : (offers.size() + COLS - 1) / COLS;
			int width = GAP + COLS * (cW + GAP);
			int height = GAP + Math.max(VISIBLE_ROWS, rows) * (cH + GAP);
			return new Dimension(width, height);
		}

		private int hitIndex(int x, int y)
		{
			for (int i = 0; i < cardBounds.size(); i++)
			{
				if (cardBounds.get(i).contains(x, y))
				{
					return i;
				}
			}
			return -1;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			List<Rectangle> painted = new ArrayList<>();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.clipRect(0, 0, getWidth(), getHeight());
				Insets ins = getInsets();
				if (offers.isEmpty())
				{
					g2.setColor(new Color(0xAAAAAA));
					g2.setFont(FontManager.getRunescapeSmallFont());
					String empty = localSide ? "No cards offered" : "Waiting for offers…";
					g2.drawString(empty, ins.left + 12, ins.top + 20);
					cardBounds = Collections.emptyList();
					return;
				}

				int cW = cardWidth();
				int cH = cardHeight();
				int colW = cW + GAP;
				int rowH = cH + GAP;

				for (int i = 0; i < offers.size(); i++)
				{
					int col = i % COLS;
					int row = i / COLS;
					int x = ins.left + GAP + col * colW;
					int y = ins.top + GAP + row * rowH;
					Rectangle bounds = new Rectangle(x, y, cW, cH);
					painted.add(bounds);

					TradeOfferView offer = offers.get(i);
					CardDefinition card = cardDatabase.findByName(offer.getCardName()).orElse(null);
					Color rarity = cardDatabase.chatRarityColorForCardName(offer.getCardName());
					BufferedImage art = imageCacheService.getCached(card == null ? null : card.getImageUrl());
					SharedCardRenderer.drawCardFace(g2, bounds, card, offer.isFoil(), rarity, art, 0L, offer.isFoil());
				}
			}
			finally
			{
				g2.dispose();
				cardBounds = painted;
			}
		}
	}
}
